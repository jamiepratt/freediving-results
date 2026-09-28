"""Replay the six cited San Mauro JPGs through the public CLI."""

import hashlib
import json
import subprocess
import sys
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/san_mauro_jpg_supplement.py"
NAMES = (
    "napoli_statica_maschile_2025.jpg",
    "napoli_statica_femminile_2025.jpg",
    "napoli_dinamica_maschile_2025.jpg",
    "napoli_dinamica_femminile_2025.jpg",
    "napoli_combinata_maschile_2025.jpg",
    "napoli_combinata_femminile_2025.jpg",
)


def jpg(width=100, height=100):
    # JPEG SOF0 gives dimensions; pixel decoding is outside the replay contract.
    sof = bytes.fromhex("ffc0001108") + height.to_bytes(2, "big") + width.to_bytes(2, "big")
    sof += bytes.fromhex("03011100021100031100")
    return bytes.fromhex("ffd8ffe000044a46") + sof + bytes.fromhex("ffd9")


def fixture(tmp_path):
    raw = tmp_path / "raw"
    raw.mkdir()
    sources = []
    for i, name in enumerate(NAMES, 45):
        data = jpg()
        (raw / name).write_bytes(data)
        kind = "aggregate" if "combinata" in name else "individual"
        sources.append({
            "lead_id": f"san-mauro-2025:{i}",
            "url": f"https://apnea.academy/site/assets/files/11984/{name}",
            "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "width": 100, "height": 100,
            "competition_date": {"start": "2025-02-01", "end": "2025-02-02"},
            "date_evidence": {"url": "https://apnea.academy/example",
                              "quote": "Giro d'Italia in Apnea 2025 TROFEO SAN MAURO "
                                       "Casalnuovo di Napoli (NA) - 1 / 2 Febbraio"},
            "kind": kind,
            "regions": [{"id": "ranking", "bbox": [0, 0, 100, 100],
                         "printed_row_count": 1, "rows": [1]}],
            "rows": [{"region_id": "ranking", "printed_row": 1, "rank": 1,
                      "bbox": [1, 10, 99, 20], "raw_fields": {"name": "Athlete", "result": "1:00"},
                      "uncertain_fields": [], "status": None, "notes": None,
                      "disposition": "transcribed"}],
        })
    ledger = tmp_path / "ledger.json"
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    return raw, ledger, sources


def run(tmp_path, raw, ledger):
    output = tmp_path / "packet.json"
    result = subprocess.run([sys.executable, str(SCRIPT), "--ledger", str(ledger),
                             "--raw-dir", str(raw), "--output", str(output)],
                            capture_output=True, text=True)
    return result, output


def test_replays_six_images_with_separate_source_positions_and_versions(tmp_path):
    raw, ledger, _ = fixture(tmp_path)
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode == 0, result.stderr
    packet = json.loads(output.read_text())
    assert packet["counts"] == {"source_objects": 6, "individual_positions": 4,
                                "aggregate_positions": 2, "source_positions": 6,
                                "observation_versions": 6,
                                "transcribed_positions": 6, "unresolved_positions": 0,
                                "manual_observation_versions": 6,
                                "imported_observation_versions": 0,
                                "relationship_candidates": 0,
                                "confirmed_distinct_attempts": None}
    assert packet["positions"][0]["source_id"].startswith("sha256:")
    assert packet["positions"][0]["citation"]["bbox"] == [1, 10, 99, 20]
    assert packet["observation_versions"][0]["raw_fields"]["result"] == "1:00"
    assert packet["source_objects"][0]["width"] == 100
    first = output.read_bytes()
    result, _ = run(tmp_path, raw, ledger)
    assert result.returncode == 0 and output.read_bytes() == first


def test_rejects_changed_original_and_missing_position(tmp_path):
    raw, ledger, sources = fixture(tmp_path)
    (raw / NAMES[0]).write_bytes(jpg(width=101))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "SHA-256" in result.stderr
    assert not output.exists()
    (raw / NAMES[0]).write_bytes(jpg())
    sources[0]["regions"][0]["printed_row_count"] = 2
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "printed row" in result.stderr.lower()
    assert not output.exists()


def test_preserves_unresolved_printed_row_and_rejects_overlap(tmp_path):
    raw, ledger, sources = fixture(tmp_path)
    first = sources[0]
    first["regions"][0]["printed_row_count"] = 2
    first["regions"][0]["rows"] = [1, 2]
    first["rows"].append({"region_id": "ranking", "printed_row": 2,
                          "bbox": [1, 21, 99, 31], "raw_fields": {"name": ""},
                          "uncertain_fields": ["name"], "status": None,
                          "notes": None, "disposition": "unresolved",
                          "unresolved_reason": "Name clipped at right edge"})
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode == 0, result.stderr
    packet = json.loads(output.read_text())
    unresolved = [v for v in packet["observation_versions"]
                  if v["disposition"] == "unresolved"]
    assert len(unresolved) == 1
    assert unresolved[0]["unresolved_reason"] == "Name clipped at right edge"
    assert packet["counts"]["individual_positions"] == 5
    assert packet["counts"]["source_positions"] == 7
    assert packet["counts"]["transcribed_positions"] == 6
    assert packet["counts"]["unresolved_positions"] == 1
    assert packet["counts"]["manual_observation_versions"] == 7
    assert packet["counts"]["imported_observation_versions"] == 0
    assert packet["counts"]["confirmed_distinct_attempts"] is None
    output.unlink()
    first["rows"][1]["bbox"] = [1, 19, 99, 31]
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "overlapping" in result.stderr
    assert not output.exists()


def test_rejects_date_claim_without_matching_heading(tmp_path):
    raw, ledger, sources = fixture(tmp_path)
    sources[0]["date_evidence"]["quote"] = "Other date"
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "date citation" in result.stderr.lower()
    assert not output.exists()


def test_replays_optional_columns_and_verified_http_receipt(tmp_path):
    raw, ledger, sources = fixture(tmp_path)
    source = sources[0]
    headers = b"HTTP/2 200 \r\ncontent-type: image/jpeg\r\ncontent-length: 29\r\ndate: Mon, 28 Sep 2026 19:52:41 GMT\r\n\r\n"
    # Derive the byte count from the retained source, not a fixture assumption.
    headers = headers.replace(b"content-length: 29", f"content-length: {source['bytes']}".encode())
    header_path = tmp_path / "retained.headers"
    header_path.write_bytes(headers)
    source["columns"] = ["name", "result"]
    source["raw_path"] = str((raw / NAMES[0]).resolve())
    source["receipt"] = {"status": 200, "redirects": [],
                         "headers_path": str(header_path),
                         "headers_sha256": hashlib.sha256(headers).hexdigest(),
                         "http_version": "HTTP/2", "content_type": "image/jpeg",
                         "content_length": source["bytes"], "last_modified": None,
                         "response_date": "Mon, 28 Sep 2026 19:52:41 GMT"}
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode == 0, result.stderr
    first = json.loads(output.read_text())["source_objects"][0]
    assert first["columns"] == ["name", "result"]
    assert first["receipt"]["headers_sha256"] == source["receipt"]["headers_sha256"]
    output.unlink()
    source["rows"][0]["raw_fields"]["result"] = "1:01"
    source["rows"][0]["raw_fields"].pop("name")
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "columns" in result.stderr.lower()
    assert not output.exists()


def test_rejects_unverified_http_headers(tmp_path):
    raw, ledger, sources = fixture(tmp_path)
    source = sources[0]
    headers = f"HTTP/2 200\r\ncontent-type: image/jpeg\r\ncontent-length: {source['bytes']}\r\ndate: Mon, 28 Sep 2026 19:52:41 GMT\r\n\r\n".encode()
    header_path = tmp_path / "retained.headers"
    header_path.write_bytes(headers)
    source["receipt"] = {"status": 200, "redirects": [],
                         "headers_path": str(header_path),
                         "headers_sha256": "0" * 64,
                         "http_version": "HTTP/2", "content_type": "image/jpeg",
                         "content_length": source["bytes"], "last_modified": None,
                         "response_date": "Mon, 28 Sep 2026 19:52:41 GMT"}
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "headers sha" in result.stderr.lower()
    assert not output.exists()


def test_preserves_source_role_and_note(tmp_path):
    raw, ledger, sources = fixture(tmp_path)
    sources[0]["source_role"] = "individual static results"
    sources[0]["source_note"] = "Original printed table, separate from combined standings."
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode == 0, result.stderr
    first = json.loads(output.read_text())["source_objects"][0]
    assert first["source_role"] == "individual static results"
    assert first["source_note"] == "Original printed table, separate from combined standings."
    output.unlink()
    sources[0]["source_note"] = None
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode == 0, result.stderr
    assert json.loads(output.read_text())["source_objects"][0]["source_note"] is None
    output.unlink()
    sources[0]["source_note"] = "  "
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "source_note" in result.stderr
    assert not output.exists()


def test_emits_only_cited_same_category_event_context_candidates(tmp_path):
    raw, ledger, sources = fixture(tmp_path)
    relations = [
        {"left_lead_id": "san-mauro-2025:45", "right_lead_id": "san-mauro-2025:49",
         "state": "candidate_event_context", "basis": "Same printed event and male category; row equivalence unproved."},
        {"left_lead_id": "san-mauro-2025:50", "right_lead_id": "san-mauro-2025:48",
         "state": "candidate_event_context", "basis": "Same printed event and female category; row equivalence unproved."},
    ]
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources,
                                  "relationships": relations}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode == 0, result.stderr
    packet = json.loads(output.read_text())
    assert packet["counts"]["relationship_candidates"] == 2
    assert len(packet["relationships"]) == 2
    assert packet["relationships"][0]["left_source_sha256"] == sources[0]["sha256"]
    assert packet["relationships"][0]["right_source_sha256"] == sources[4]["sha256"]
    assert "2025" in packet["relationships"][0]["evidence"]["left_date_evidence"]["quote"]
    assert packet["counts"]["confirmed_distinct_attempts"] is None
    output.unlink()
    relations.append({"left_lead_id": "san-mauro-2025:49",
                      "right_lead_id": "san-mauro-2025:45",
                      "state": "candidate_event_context", "basis": "Duplicate in reverse."})
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources,
                                  "relationships": relations}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "duplicate" in result.stderr.lower()
    assert not output.exists()
    relations[-1]["right_lead_id"] = "san-mauro-2025:46"
    ledger.write_text(json.dumps({"schema": "san-mauro-jpg-ledger/v1", "sources": sources,
                                  "relationships": relations}))
    result, output = run(tmp_path, raw, ledger)
    assert result.returncode != 0 and "category" in result.stderr.lower()
    assert not output.exists()
