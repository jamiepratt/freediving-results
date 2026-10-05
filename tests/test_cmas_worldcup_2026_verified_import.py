"""Public CLI contract for staging independently checked World Cup rows."""

import hashlib
import json
import sqlite3
import struct
import subprocess
import sys
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/cmas_worldcup_2026_verified_import.py"
FIELDS = ("Last Name", "First Name", "Country", "Discipline", "AP (m)",
          "Top time", "Dive Time", "RP (m)", "Card", "Record", "Points")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, sort_keys=True), encoding="utf-8")
    return digest(path.read_bytes())


def fixture(tmp_path):
    pdf = tmp_path / "source.pdf"
    pdf.write_bytes(b"synthetic PDF bytes")
    source_hash = digest(pdf.read_bytes())
    render = tmp_path / "page.png"
    render.write_bytes(b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" +
                       struct.pack(">II", 100, 100))
    render_hash = digest(render.read_bytes())
    def fields(name):
        return {key: (name if key == "Last Name" else "Athlete" if key == "First Name"
                      else "80" if key in ("AP (m)", "RP (m)", "Points") else None)
                for key in FIELDS}
    rows = [{"id": f"position-{i}", "row": i,
             "citation": {"page": 1, "region": {"units": "rendered_px_180dpi",
                                               "bbox": [0, 10*i, 100, 10*i+9]}},
             "fields": fields(f"Name{i}"), "uncertainties": [],
             "transcription_notes": ["printed mark retained"] if i == 1 else []}
            for i in (1, 2, 3)]
    packet = {"schema": "cmas-worldcup-2026-visual-evidence/v1",
              "source_sha256": source_hash, "source_bytes": pdf.stat().st_size,
              "source": {"id": f"sha256:{source_hash}", "original_sha256": source_hash},
              "pages": [{"page": 1, "render": {"sha256": render_hash,
                         "pixel_width": 100, "pixel_height": 100}, "rows": rows}],
              "counts": {"source_positions": 3}}
    second = {"schema": "cmas-worldcup-2026-second-pass/v1", "source_sha256": source_hash,
              "blind_to_first_pass": True, "transcriber": "independent fixture pass",
              "rows": [{"page": 1, "row": row["row"], "fields": dict(row["fields"]),
                        "uncertain_fields": []} for row in rows]}
    second["rows"][0]["fields"]["RP (m)"] = "8O"
    receipt = {"source_sha256": source_hash, "bytes": pdf.stat().st_size,
               "acquisition": "synthetic retained receipt"}
    packet_path, second_path, receipt_path = (tmp_path / name for name in
                                             ("packet.json", "second.json", "receipt.json"))
    packet_hash = save(packet_path, packet)
    second_hash = save(second_path, second)
    receipt_hash = save(receipt_path, receipt)
    inspected = {"schema": "cmas-worldcup-2026-image-inspections/v1",
                 "source_sha256": source_hash, "records": []}
    inspection_path = tmp_path / "inspections.json"
    save(inspection_path, inspected)
    output = tmp_path / "staged.json"
    def run(parser_version="fixture-v1", output_path=None):
        args = [sys.executable, str(SCRIPT), "--pdf", str(pdf), "--packet", str(packet_path),
                "--packet-sha256", packet_hash, "--second-pass", str(second_path),
                "--second-pass-sha256", digest(second_path.read_bytes()), "--receipt", str(receipt_path),
                "--receipt-sha256", receipt_hash, "--render", str(render),
                "--inspections", str(inspection_path),
                "--inspections-sha256", digest(inspection_path.read_bytes()),
                "--output", str(output_path or output),
                "--expected-source-sha256", source_hash, "--parser-version", parser_version]
        return subprocess.run(args, capture_output=True, text=True)
    return locals()


def test_stages_only_inspected_agreements_and_disagreement_with_raw_provenance(tmp_path):
    f = fixture(tmp_path)
    assert f["run"]().returncode != 0
    assert not f["output"].exists()
    for i, reason in ((1, "disagreement"), (2, "agreement_sample"), (3, "agreement_sample")):
        f["inspected"]["records"].append({"page": 1, "row": i, "reason": reason,
            "render_sha256": f["render_hash"], "bbox": [0, 10*i, 100, 10*i+9],
            "fields": f["rows"][i-1]["fields"], "uncertain_fields": [],
            "inspector": "fixture image check"})
    save(f["inspection_path"], f["inspected"])
    result = f["run"]()
    assert result.returncode == 0, result.stderr
    staged = json.loads(f["output"].read_text())
    assert staged["counts"]["cited_positions"] == 3
    assert staged["counts"]["verified_staged_versions"] == 3
    assert staged["counts"]["unresolved_positions"] == 0
    assert staged["counts"]["confirmed_distinct_attempts"] is None
    assert staged["observation_versions"][0]["raw_fields"]["RP (m)"] == "80"
    assert staged["observation_versions"][0]["first_pass_raw_fields"]["RP (m)"] == "80"
    assert staged["observation_versions"][0]["second_pass_raw_fields"]["RP (m)"] == "8O"
    assert staged["observation_versions"][0]["interpreted_fields"] == {}
    assert staged["observation_versions"][0]["transcription_notes"] == ["printed mark retained"]
    assert staged["observation_versions"][0]["source_role"] == "day_primary_result"
    original = f["output"].read_bytes()
    assert f["run"]().returncode == 0
    assert f["output"].read_bytes() == original


def test_changed_source_or_parser_version_does_not_overwrite_staged_artifact(tmp_path):
    f = fixture(tmp_path)
    f["pdf"].write_bytes(b"changed")
    assert f["run"]().returncode != 0
    assert not f["output"].exists()


def test_compare_only_lists_every_disagreement_and_deterministic_sample(tmp_path):
    f = fixture(tmp_path)
    args = [sys.executable, str(SCRIPT), "--pdf", str(f["pdf"]), "--packet", str(f["packet_path"]),
            "--packet-sha256", f["packet_hash"], "--second-pass", str(f["second_path"]),
            "--second-pass-sha256", f["second_hash"], "--receipt", str(f["receipt_path"]),
            "--receipt-sha256", f["receipt_hash"], "--render", str(f["render"]),
            "--output", str(f["output"]), "--expected-source-sha256", f["source_hash"],
            "--parser-version", "fixture-v1", "--compare-only"]
    result = subprocess.run(args, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    report = json.loads(f["output"].read_text())
    assert report["schema"] == "cmas-worldcup-2026-comparison/v1"
    assert report["disagreements"] == [{"page": 1, "row": 1, "fields": ["RP (m)"]}]
    assert len(report["sampled_agreements"]) == 1
    assert report["sampled_agreements"][0] in ([1, 2], [1, 3])


def test_uncertain_cell_stays_unresolved_after_two_matching_readings(tmp_path):
    f = fixture(tmp_path)
    f["second"]["rows"][0]["fields"]["RP (m)"] = "80"
    f["second"]["rows"][0]["uncertain_fields"] = ["RP (m)"]
    f["second_hash"] = save(f["second_path"], f["second"])
    f["inspected"]["records"].append({"page": 1, "row": 1, "reason": "disagreement",
        "render_sha256": f["render_hash"], "bbox": [0, 10, 100, 19],
        "fields": f["rows"][0]["fields"], "uncertain_fields": ["RP (m)"],
        "inspector": "fixture image check"})
    for i in (2, 3):
        f["inspected"]["records"].append({"page": 1, "row": i,
            "reason": "agreement_sample", "render_sha256": f["render_hash"],
            "bbox": [0, 10*i, 100, 10*i+9], "fields": f["rows"][i-1]["fields"],
            "uncertain_fields": [], "inspector": "fixture image check"})
    save(f["inspection_path"], f["inspected"])
    result = f["run"]()
    assert result.returncode == 0, result.stderr
    staged = json.loads(f["output"].read_text())
    assert staged["counts"]["verified_staged_versions"] == 2
    assert staged["unresolved_positions"][0]["row"] == 1
    assert staged["unresolved_positions"][0]["second_pass_uncertain_fields"] == ["RP (m)"]
    assert staged["unresolved_positions"][0]["first_pass_raw_fields"]["RP (m)"] == "80"
    assert staged["unresolved_positions"][0]["inspection"]["render_sha256"] == f["render_hash"]


def test_rejects_duplicate_second_position_and_preserves_existing_stage(tmp_path):
    f = fixture(tmp_path)
    f["second"]["rows"].append(dict(f["second"]["rows"][0]))
    f["second_hash"] = save(f["second_path"], f["second"])
    f["output"].write_bytes(b"previous staged artifact")
    result = f["run"]()
    assert result.returncode != 0
    assert "duplicate" in result.stderr
    assert f["output"].read_bytes() == b"previous staged artifact"


def test_changed_parser_version_needs_new_stage_path_and_version_ids(tmp_path):
    f = fixture(tmp_path)
    for i, reason in ((1, "disagreement"), (2, "agreement_sample"), (3, "agreement_sample")):
        f["inspected"]["records"].append({"page": 1, "row": i, "reason": reason,
            "render_sha256": f["render_hash"], "bbox": [0, 10*i, 100, 10*i+9],
            "fields": f["rows"][i-1]["fields"], "uncertain_fields": [],
            "inspector": "fixture image check"})
    save(f["inspection_path"], f["inspected"])
    assert f["run"]().returncode == 0
    first = json.loads(f["output"].read_text())
    original = f["output"].read_bytes()
    assert f["run"](parser_version="fixture-v2").returncode != 0
    assert f["output"].read_bytes() == original
    next_output = tmp_path / "stage-v2.json"
    assert f["run"](parser_version="fixture-v2", output_path=next_output).returncode == 0
    second = json.loads(next_output.read_text())
    assert second["observation_versions"][0]["id"] != first["observation_versions"][0]["id"]


def test_isolated_store_import_replays_and_rolls_back_conflicting_batch(tmp_path):
    source = "a" * 64
    def version(position, parser="fixture-v1", value="80"):
        return {"id": f"observation-version:{parser}:{position}:{value}",
                "source_sha256": source, "parser_version": parser,
                "source_position": {"id": position, "page": 1, "row": 1},
                "raw_fields": {"RP (m)": value}, "interpreted_fields": {}}
    stage = {"schema": "cmas-worldcup-2026-verified-stage/v1", "source_sha256": source,
             "parser_version": "fixture-v1", "observation_versions":
             [version("position-1"), version("position-2")], "unresolved_positions": [],
             "counts": {"cited_positions": 2, "verified_staged_versions": 2,
                        "unresolved_positions": 0, "confirmed_distinct_attempts": None}}
    stage_path = tmp_path / "stage.json"
    store_path = tmp_path / "isolated.sqlite"
    def run():
        return subprocess.run([sys.executable, str(SCRIPT), "--import-stage", str(stage_path),
            "--stage-sha256", digest(stage_path.read_bytes()), "--sqlite-store", str(store_path)],
            capture_output=True, text=True)
    save(stage_path, stage)
    assert run().returncode == 0
    assert store_path.stat().st_mode & 0o777 == 0o600
    assert run().returncode == 0
    with sqlite3.connect(store_path) as db:
        assert db.execute("select count(*) from observation_versions").fetchone()[0] == 2
        assert db.execute("select count(*) from stage_imports").fetchone()[0] == 1
    # A parser revision adds versions without replacing the first parser's evidence.
    stage["parser_version"] = "fixture-v2"
    stage["observation_versions"] = [version("position-1", "fixture-v2"),
                                      version("position-2", "fixture-v2")]
    save(stage_path, stage)
    assert run().returncode == 0
    with sqlite3.connect(store_path) as db:
        assert db.execute("select count(*) from observation_versions").fetchone()[0] == 4
        assert db.execute("select count(*) from stage_imports").fetchone()[0] == 2
    # The first insert in this batch is new; the second conflicts with v1.
    stage["parser_version"] = "fixture-v1"
    stage["observation_versions"] = [version("position-0"),
                                      version("position-2", value="81")]
    save(stage_path, stage)
    result = run()
    assert result.returncode != 0
    with sqlite3.connect(store_path) as db:
        assert db.execute("select count(*) from observation_versions").fetchone()[0] == 4
        assert db.execute("select count(*) from observation_versions where source_position_id = 'position-0'").fetchone()[0] == 0
        assert db.execute("select count(*) from stage_imports").fetchone()[0] == 2
