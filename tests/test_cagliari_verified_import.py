"""Public CLI checks for the private Cagliari verification route."""

import hashlib
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import zlib


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/cagliari_verified_import.py"


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def saved(path, value):
    raw = (json.dumps(value, sort_keys=True) + "\n").encode()
    path.write_bytes(raw)
    return digest(raw)


def png():
    def chunk(tag, payload):
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", zlib.crc32(tag + payload))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 10, 10, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00" + b"\xff\xff\xff" * 10) * 10) + chunk(b"IEND", b""))


def fixture(tmp_path, second_value="4", inspect_value="4", uncertain=False):
    source = tmp_path / "source.pdf"
    source.write_bytes(b"%PDF-1.4\nsynthetic\n")
    source_hash = digest(source.read_bytes())
    render = tmp_path / "page.png"
    render.write_bytes(png())
    render_hash = digest(render.read_bytes())
    row_id = "athlete-position:" + digest(f"{source_hash}:page:1:athlete:row:1".encode())
    row = {"id": row_id, "row": 1, "kind": "athlete", "fields_raw": {"Punteggio": "4"},
           "citation": {"page": 1, "region": {"units": "upright_png_pixels_220dpi", "bbox": [1, 1, 9, 9]}},
           "uncertainties": [], "status_raw": None, "penalty_raw": None, "notes_raw": None}
    packet = {"schema": "cagliari-visual-evidence/v1", "source_sha256": source_hash,
              "source_bytes": source.stat().st_size,
              "source": {"id": f"sha256:{source_hash}", "original_sha256": source_hash},
              "pages": [{"page": 1, "page_kind": "result", "rows": [row], "visual_row_count": 1,
                         "heading_raw": "test", "category_raw": "test", "discipline_raw": "test",
                         "event_date_printed": "2026-01-18"}],
              "counts": {"source_positions": 1, "athlete_row_appearances": 1,
                         "aggregate_row_appearances": 0, "aggregate_rows_excluded": 0,
                         "nonduplicate_candidate_positions": 1}}
    second = {"schema": "cagliari-second-pass/v1", "source_sha256": source_hash,
              "blind_to_first_pass": True, "transcriber": "blind tester",
              "rows": [{"page": 1, "row": 1, "kind": "athlete",
                        "fields_raw": {"Punteggio": second_value},
                        "uncertain_fields": ["Punteggio"] if uncertain else []}]}
    receipt = {"source_sha256": source_hash, "bytes": source.stat().st_size}
    inspections = {"schema": "cagliari-image-inspections/v1", "source_sha256": source_hash,
                   "records": [{"page": 1, "row": 1,
                                "reason": "disagreement" if second_value != "4" else "agreement_sample",
                                "render_sha256": render_hash, "bbox": [1, 1, 9, 9],
                                "inspector": "image tester", "fields_raw": {"Punteggio": inspect_value},
                                "uncertain_fields": ["Punteggio"] if uncertain else []}]}
    sha = {name: saved(tmp_path / f"{name}.json", doc) for name, doc in
           (("packet", packet), ("second", second), ("receipt", receipt), ("inspections", inspections))}
    return ["--pdf", str(source), "--expected-source-sha256", source_hash,
            "--packet", str(tmp_path / "packet.json"), "--packet-sha256", sha["packet"],
            "--second-pass", str(tmp_path / "second.json"), "--second-pass-sha256", sha["second"],
            "--receipt", str(tmp_path / "receipt.json"), "--receipt-sha256", sha["receipt"],
            "--inspections", str(tmp_path / "inspections.json"), "--inspections-sha256", sha["inspections"],
            "--render", str(render), "--parser-version", "test-v1", "--output", str(tmp_path / "stage.json")]


def invoke(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


def test_verified_cagliari_stage_replays_and_retains_parser_versions(tmp_path):
    args = fixture(tmp_path)
    result = invoke(*args)
    assert result.returncode == 0, result.stderr
    stage_path = tmp_path / "stage.json"
    stage = json.loads(stage_path.read_text())
    assert stage["counts"]["candidate_result_positions"] == 1
    assert len(stage["observation_versions"]) == 1
    assert invoke(*args).returncode == 0
    store = tmp_path / "store.sqlite"
    import_args = ["--import-stage", str(stage_path), "--stage-sha256", digest(stage_path.read_bytes()),
                   "--sqlite-store", str(store)]
    assert invoke(*import_args).returncode == 0
    assert invoke(*import_args).returncode == 0
    with sqlite3.connect(store) as db:
        assert db.execute("select count(*) from observation_versions").fetchone() == (1,)
        assert db.execute("pragma integrity_check").fetchone() == ("ok",)
    args[args.index("test-v1")] = "test-v2"
    args[-1] = str(tmp_path / "stage-v2.json")
    assert invoke(*args).returncode == 0
    new_stage = tmp_path / "stage-v2.json"
    assert invoke("--import-stage", str(new_stage), "--stage-sha256", digest(new_stage.read_bytes()),
                  "--sqlite-store", str(store)).returncode == 0
    with sqlite3.connect(store) as db:
        assert db.execute("select count(*) from observation_versions").fetchone() == (2,)


def test_disagreement_needs_inspection_and_uncertain_reading_stays_unresolved(tmp_path):
    args = fixture(tmp_path, second_value="5", inspect_value="4", uncertain=True)
    result = invoke(*args)
    assert result.returncode == 0, result.stderr
    stage = json.loads((tmp_path / "stage.json").read_text())
    assert stage["counts"]["unresolved_positions"] == 1
    assert stage["observation_versions"] == []


def test_hash_mismatch_rejects_before_store_mutation(tmp_path):
    args = fixture(tmp_path)
    assert invoke(*args).returncode == 0
    stage = tmp_path / "stage.json"
    store = tmp_path / "store.sqlite"
    result = invoke("--import-stage", str(stage), "--stage-sha256", "0" * 64,
                    "--sqlite-store", str(store))
    assert result.returncode != 0
    assert not store.exists()


def test_presentation_differences_keep_both_raw_readings(tmp_path):
    args = fixture(tmp_path)
    second_path = tmp_path / "second.json"
    second = json.loads(second_path.read_text())
    second["rows"][0]["fields_raw"]["Punteggio"] = "4"
    second["rows"][0]["fields_raw"]["Posizione"] = 1
    packet_path = tmp_path / "packet.json"
    packet = json.loads(packet_path.read_text())
    packet["pages"][0]["rows"][0]["fields_raw"]["Posizione"] = "1"
    inspections_path = tmp_path / "inspections.json"
    inspections = json.loads(inspections_path.read_text())
    inspections["records"][0]["fields_raw"]["Posizione"] = "1"
    args[args.index("--packet-sha256") + 1] = saved(packet_path, packet)
    args[args.index("--second-pass-sha256") + 1] = saved(second_path, second)
    args[args.index("--inspections-sha256") + 1] = saved(inspections_path, inspections)
    result = invoke(*args)
    assert result.returncode == 0, result.stderr
    stage = json.loads((tmp_path / "stage.json").read_text())
    assert stage["observation_versions"][0]["second_pass_raw_fields"]["Posizione"] == 1
    assert stage["verification"]["presentation_differences"] == [[1, 1, ["Posizione"]]]


def test_conflicting_stage_rolls_back_existing_store(tmp_path):
    args = fixture(tmp_path)
    assert invoke(*args).returncode == 0
    stage_path = tmp_path / "stage.json"
    store = tmp_path / "store.sqlite"
    assert invoke("--import-stage", str(stage_path), "--stage-sha256", digest(stage_path.read_bytes()),
                  "--sqlite-store", str(store)).returncode == 0
    original = json.loads(stage_path.read_text())
    conflicting = json.loads(stage_path.read_text())
    conflicting["observation_versions"][0]["raw_fields"]["Punteggio"] = "999"
    bad_path = tmp_path / "conflicting.json"
    bad_sha = saved(bad_path, conflicting)
    result = invoke("--import-stage", str(bad_path), "--stage-sha256", bad_sha,
                    "--sqlite-store", str(store))
    assert result.returncode != 0
    with sqlite3.connect(store) as db:
        assert db.execute("select count(*) from observation_versions").fetchone() == (1,)
        assert db.execute("select count(*) from stage_imports").fetchone() == (1,)
        assert db.execute("pragma integrity_check").fetchone() == ("ok",)
    assert original["counts"]["verified_staged_versions"] == 1
