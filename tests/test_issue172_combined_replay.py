"""Combined replay is observed through its private command line."""

import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/issue172_combined_replay.py"


def saved(path, value):
    path.write_text(json.dumps(value, sort_keys=True), encoding="utf-8")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fixture(tmp_path, names=("a", "b")):
    entries = []
    for name in names:
        source = tmp_path / f"{name}.pdf"
        source.write_bytes(f"source {name}".encode())
        source_sha = hashlib.sha256(source.read_bytes()).hexdigest()
        first = tmp_path / f"{name}-first.json"
        first_sha = saved(first, {"source_sha256": source_sha})
        position = f"position:{name}"
        stage = {"schema": "fipsas-image-verified-stage/v1", "source_sha256": source_sha,
                 "parser_version": "fixture-v1", "input_sha256": {"first_packet": first_sha},
                 "counts": {"cited_positions": 2, "candidate_result_positions": 1,
                            "verified_staged_versions": 1, "unresolved_positions": 0,
                            "non_primary_positions": 1},
                 "observation_versions": [{"id": f"observation-version:{name}-v1",
                     "source_sha256": source_sha, "parser_version": "fixture-v1",
                     "source_position": {"id": position, "citation": {"page": 1}},
                     "raw_fields": {"AP": "80"}, "interpreted_fields": {}}],
                 "unresolved_positions": [], "non_primary_positions": [
                     {"id": f"aggregate:{name}", "citation": {"page": 1},
                      "disposition": "aggregate"}]}
        stage_path = tmp_path / f"{name}-stage.json"
        stage_sha = saved(stage_path, stage)
        entries.append({"name": name, "stage": {"path": str(stage_path), "sha256": stage_sha},
                        "source": {"path": str(source), "sha256": source_sha},
                        "inputs": {"first_packet": {"path": str(first), "sha256": first_sha}}})
    spec = tmp_path / "spec.json"
    saved(spec, {"schema": "issue172-combined-input/v1", "sources": entries})
    output = tmp_path / "out"
    def run():
        return subprocess.run([sys.executable, str(SCRIPT), "--input-spec", str(spec),
                               "--output-dir", str(output), "--expected-sources", str(len(names))],
                              capture_output=True, text=True)
    return locals()


def test_replays_two_sources_once_and_preserves_raw_versions(tmp_path):
    f = fixture(tmp_path)
    result = f["run"]()
    assert result.returncode == 0, result.stderr
    manifest = json.loads((f["output"] / "manifest-v1.json").read_text())
    assert manifest["counts"]["candidate_result_positions"] == 2
    assert manifest["counts"]["verified_staged_versions"] == 2
    db_path = f["output"] / "combined-isolated-v1.sqlite"
    with sqlite3.connect(db_path) as db:
        assert db.execute("select count(*) from observation_versions").fetchone()[0] == 2
        assert db.execute("select raw_json from observation_versions order by id").fetchone()[0] == '{"AP":"80"}'
    before = [p.read_bytes() for p in (f["output"] / "manifest-v1.json", db_path,
                                        f["output"] / "replay-proof-v1.json")]
    assert f["run"]().returncode == 0
    assert before == [p.read_bytes() for p in (f["output"] / "manifest-v1.json", db_path,
                                             f["output"] / "replay-proof-v1.json")]


def test_missing_or_changed_input_cannot_replace_existing_replay(tmp_path):
    f = fixture(tmp_path)
    assert f["run"]().returncode == 0
    old = (f["output"] / "combined-isolated-v1.sqlite").read_bytes()
    Path(f["entries"][1]["inputs"]["first_packet"]["path"]).write_bytes(b"changed")
    assert f["run"]().returncode != 0
    assert (f["output"] / "combined-isolated-v1.sqlite").read_bytes() == old


def test_duplicate_position_or_partial_failure_never_publishes(tmp_path):
    f = fixture(tmp_path)
    stage_path = Path(f["entries"][1]["stage"]["path"])
    stage = json.loads(stage_path.read_text())
    stage["observation_versions"][0]["source_position"]["id"] = "position:a"
    f["entries"][1]["stage"]["sha256"] = saved(stage_path, stage)
    saved(f["spec"], {"schema": "issue172-combined-input/v1", "sources": f["entries"]})
    assert f["run"]().returncode != 0
    assert not f["output"].exists()


def test_changed_parser_retains_prior_version_and_source(tmp_path):
    f = fixture(tmp_path, names=("a",))
    assert f["run"]().returncode == 0
    stage_path = Path(f["entries"][0]["stage"]["path"])
    stage = json.loads(stage_path.read_text())
    stage["parser_version"] = "fixture-v2"
    stage["observation_versions"][0]["parser_version"] = "fixture-v2"
    stage["observation_versions"][0]["id"] = "observation-version:a-v2"
    f["entries"][0]["stage"]["sha256"] = saved(stage_path, stage)
    saved(f["spec"], {"schema": "issue172-combined-input/v1", "sources": f["entries"]})
    assert f["run"]().returncode == 0
    with sqlite3.connect(f["output"] / "combined-isolated-v1.sqlite") as db:
        assert db.execute("select parser_version from observation_versions order by parser_version").fetchall() == [
            ("fixture-v1",), ("fixture-v2",)]


def test_late_conflict_rolls_back_new_parser_rows(tmp_path):
    f = fixture(tmp_path)
    assert f["run"]().returncode == 0
    db_path = f["output"] / "combined-isolated-v1.sqlite"
    before = db_path.read_bytes()
    first = Path(f["entries"][0]["stage"]["path"])
    a = json.loads(first.read_text())
    a["parser_version"] = "fixture-v2"
    a["observation_versions"][0]["parser_version"] = "fixture-v2"
    a["observation_versions"][0]["id"] = "observation-version:a-v2"
    f["entries"][0]["stage"]["sha256"] = saved(first, a)
    second = Path(f["entries"][1]["stage"]["path"])
    b = json.loads(second.read_text())
    b["observation_versions"][0]["raw_fields"]["AP"] = "changed"
    f["entries"][1]["stage"]["sha256"] = saved(second, b)
    saved(f["spec"], {"schema": "issue172-combined-input/v1", "sources": f["entries"]})
    assert f["run"]().returncode != 0
    assert db_path.read_bytes() == before


def test_tampered_but_integral_store_is_not_accepted_as_unchanged(tmp_path):
    f = fixture(tmp_path)
    assert f["run"]().returncode == 0
    db_path = f["output"] / "combined-isolated-v1.sqlite"
    with sqlite3.connect(db_path) as db:
        db.execute("update observation_versions set raw_json='{}' where id='observation-version:a-v1'")
    result = f["run"]()
    assert result.returncode != 0
    assert "existing observation replay mismatch" in result.stderr
