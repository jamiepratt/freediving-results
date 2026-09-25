#!/usr/bin/env python3
"""Export, verify and restore a private, relocatable isolated corpus."""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path, PurePosixPath

SCHEMA = "portable-corpus/v1"
SHA = re.compile(r"^[0-9a-f]{64}$")


def fail(message):
    raise ValueError(message)


def relative(value):
    if not isinstance(value, str) or not value or "\\" in value:
        fail("invalid relative path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(x in ("", ".", "..") for x in value.split("/")):
        fail("unsafe relative path: " + value)
    return path


def safe_existing(path):
    path = Path(path).absolute()
    # macOS exposes system temporary directories through /var and /tmp aliases.
    if any(str(path) == alias or str(path).startswith(alias + "/") for alias in ("/var", "/tmp")):
        path = Path("/private") / path.relative_to("/")
    for part in (path, *path.parents):
        if part.is_symlink():
            fail("symlink forbidden: " + str(part))
    return path


def hash_file(path):
    h = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(block)
            h.update(block)
    return h.hexdigest(), size


def bytes_json(path):
    return json.loads(path.read_bytes())


def write_json(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n")
    path.chmod(0o600)


def index_files(spec):
    files = {}
    dirs = set()
    for entry in spec["entries"]:
        source = safe_existing(entry["source"])
        target = relative(entry["path"])
        role = entry["role"]
        if not isinstance(role, str) or not role:
            fail("entry role required")
        if source.is_file():
            members = [(source, target)]
        elif source.is_dir():
            members = []
            for parent, subdirs, filenames in os.walk(source, followlinks=False):
                current = Path(parent)
                safe_existing(current)
                prefix = target / current.relative_to(source).as_posix() if current != source else target
                dirs.add(str(prefix))
                for name in subdirs:
                    safe_existing(current / name)
                for name in filenames:
                    member = safe_existing(current / name)
                    members.append((member, prefix / name))
        else:
            fail("source must be a regular file or directory")
        for member, dest in members:
            if not member.is_file():
                fail("nonregular source file")
            name = str(relative(str(dest)))
            if name in files:
                fail("duplicate bundle path: " + name)
            sha, size = hash_file(member)
            files[name] = {"sha256": sha, "bytes": size, "role": role, "source": member}
    if not files:
        fail("bundle has no files")
    return files, sorted(dirs)


def validate_references(index, payload):
    files = index["files"]
    for ref in index.get("references", []):
        name = str(relative(ref["path"]))
        sha = ref["sha256"]
        if not SHA.fullmatch(sha) or name not in files or files[name]["sha256"] != sha:
            fail("unresolved reference: " + name)
    for root in index.get("archive_roots", []):
        root = str(relative(root))
        prefix = root + "/"
        acquisitions = [name for name in files if name.startswith(prefix + "acquisitions/") and name.endswith(".edn")]
        derivations = [name for name in files if name.startswith(prefix + "derivations/") and name.endswith(".edn")]
        if not acquisitions:
            fail("archive has no acquisitions: " + root)
        for name in acquisitions:
            match = re.search(rb':sha256\s+"([0-9a-f]{64})"', (payload / name).read_bytes())
            if not match:
                fail("acquisition missing source hash: " + name)
            sha = match.group(1).decode()
            obj = prefix + "objects/" + sha
            if obj not in files or files[obj]["sha256"] != sha:
                fail("acquisition source object absent or mismatched: " + name)
        for name in derivations:
            match = re.search(rb':artifact-sha256\s+"([0-9a-f]{64})"', (payload / name).read_bytes())
            if not match:
                fail("derivation missing artifact hash: " + name)
            sha = match.group(1).decode()
            obj = prefix + "derived-objects/" + sha
            if obj not in files or files[obj]["sha256"] != sha:
                fail("derivation artifact absent or mismatched: " + name)
    for name in index.get("receipt_paths", []):
        name = str(relative(name))
        if name not in files:
            fail("missing receipt: " + name)
        receipt = bytes_json(payload / name)
        parent = str(PurePosixPath(name).parent)
        refs = []
        if isinstance(receipt.get("source"), dict):
            refs.append((receipt["source"].get("path"), receipt["source"].get("sha256")))
        refs.extend(receipt.get("result_files", {}).items())
        for path, sha in refs:
            target = str(relative(parent + "/" + path))
            if not SHA.fullmatch(sha or "") or target not in files or files[target]["sha256"] != sha:
                fail("receipt reference absent or mismatched: " + target)


def verify(bundle):
    bundle = safe_existing(bundle)
    index_path = safe_existing(bundle / "index.json")
    index = bytes_json(index_path)
    if index.get("schema") != SCHEMA:
        fail("unsupported bundle schema")
    files = index["files"]
    if not isinstance(files, dict) or not files:
        fail("empty file index")
    payload = safe_existing(bundle / "payload")
    for name, meta in files.items():
        name = str(relative(name))
        source = safe_existing(payload / name)
        if not source.is_file() or not SHA.fullmatch(meta["sha256"]):
            fail("missing or invalid file: " + name)
        sha, size = hash_file(source)
        if (sha, size) != (meta["sha256"], meta["bytes"]):
            fail("file checksum or size mismatch: " + name)
    actual = set()
    for parent, subdirs, filenames in os.walk(payload, followlinks=False):
        for name in subdirs + filenames:
            safe_existing(Path(parent) / name)
        for name in filenames:
            actual.add((Path(parent) / name).relative_to(payload).as_posix())
    if actual != set(files):
        fail("payload contains missing or unindexed files")
    for directory in index.get("directories", []):
        path = safe_existing(payload / str(relative(directory)))
        if not path.is_dir():
            fail("missing indexed directory: " + directory)
    validate_references(index, payload)
    return index


def export(spec_path, bundle):
    spec = bytes_json(safe_existing(spec_path))
    if spec.get("schema") != "portable-corpus-spec/v1":
        fail("unsupported spec schema")
    if not isinstance(spec.get("corpus"), str) or not spec["corpus"]:
        fail("corpus name required")
    bundle = Path(bundle).absolute()
    if bundle.exists():
        fail("bundle destination already exists")
    files, dirs = index_files(spec)
    index = {"schema": SCHEMA, "corpus": spec["corpus"], "files": {}, "directories": dirs,
             "references": spec.get("references", []), "archive_roots": spec.get("archive_roots", []),
             "receipt_paths": spec.get("receipt_paths", []), "metadata": spec.get("metadata", {})}
    try:
        bundle.mkdir(mode=0o700, parents=True)
        payload = bundle / "payload"
        payload.mkdir(mode=0o700)
        for directory in dirs:
            (payload / directory).mkdir(mode=0o700, parents=True, exist_ok=True)
        for name, meta in sorted(files.items()):
            dest = payload / name
            dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            shutil.copyfile(meta["source"], dest)
            dest.chmod(0o600)
            index["files"][name] = {key: meta[key] for key in ("sha256", "bytes", "role")}
        write_json(bundle / "index.json", index)
        verify(bundle)
    except Exception:
        shutil.rmtree(bundle)
        raise
    return hash_file(bundle / "index.json")[0]


def restore(bundle, target):
    index = verify(bundle)
    target = Path(target).absolute()
    if target.exists():
        fail("restore destination already exists")
    try:
        target.mkdir(mode=0o700, parents=True)
        for directory in index["directories"]:
            (target / directory).mkdir(mode=0o700, parents=True, exist_ok=True)
        for name in index["files"]:
            dest = target / name
            dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            shutil.copyfile(Path(bundle) / "payload" / name, dest)
            dest.chmod(0o600)
        # Verify the copied bytes independently before declaring the restore ready.
        for name, meta in index["files"].items():
            if hash_file(target / name) != (meta["sha256"], meta["bytes"]):
                fail("restore copy mismatch: " + name)
    except Exception:
        shutil.rmtree(target)
        raise
    return len(index["files"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command, names in (("export", ("spec", "bundle")), ("verify", ("bundle",)), ("restore", ("bundle", "target"))):
        cmd = sub.add_parser(command)
        for name in names:
            cmd.add_argument(name)
    args = parser.parse_args()
    try:
        if args.command == "export":
            print(json.dumps({"index_sha256": export(args.spec, args.bundle)}))
        elif args.command == "verify":
            index = verify(args.bundle)
            print(json.dumps({"corpus": index["corpus"], "files": len(index["files"]),
                              "index_sha256": hash_file(Path(args.bundle) / "index.json")[0]}))
        else:
            print(json.dumps({"restored_files": restore(args.bundle, args.target)}))
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print("portable corpus: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
