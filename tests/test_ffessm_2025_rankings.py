"""The FFESSM packet replays archived ranking positions through its CLI."""

import hashlib
import json
import os
import sqlite3
import subprocess
import sys
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "ffessm_2025_rankings.py"
URLS = [
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/b81f1b021ed2ee18c7b84aa77ed5a9b1dd1f9e81.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/a1d6b065d17c907e07cb9311010a65c4921bcdb3.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/fe9c48ac711c081e9f1b098c4564dc8285748163.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/9e1c31c82820eb05495eef8d9a11b2a05ea73aff.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/a93ad9a3a528fd57641588db683e92942a5ab725.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/cbc45b63e8c11d5feffc7f7aa60dd06227033ae9.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/211dc3fd42841e867d7fa513971699745b497dd0.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/4ce0aaa231a74fdc48e8905e2889f52c03086d90.pdf",
    "https://apnee.ffessm.fr/uploads/media/default/0001/13/14733a89f0ca3dcaab4d4881eaf20f0fd032961b.pdf",
]
LABELS = ["Monopalme Femmes", "Monopalme Hommes", "Monopalme Hommes Juniors",
          "Bi-palmes Femmes", "Bi-palmes Hommes", "Sans palmes Femmes", "Sans palmes Hommes",
          "Immersion Libre Femmes", "Immersion Libre Hommes"]
HEADINGS = ["Résultats Monopalme Femmes", "Résultats Monopalme Hommes",
            "Résultats Monopalme JUNIORS", "Résultats Bipalme Femmes",
            "Résultats Bipalme Hommes", "Résultats Sans Palmes Femmes",
            "Résultats Sans Palmes Hommes", "Résultats Immersion Libre Femmes",
            "Résultats Immersion Libre Hommes"]
COUNTS = [2, 8, 1, 4, 7, 6, 12, 7, 9]
DISCIPLINES = ["CWT-MONO"] * 3 + ["CWT-BI"] * 2 + ["CNF"] * 2 + ["FIM"] * 2
CATEGORIES = ["Femmes", "Hommes", "Hommes Juniors", "Femmes", "Hommes",
              "Femmes", "Hommes", "Femmes", "Hommes"]


def pdf(title, count):
    """Small text PDF for a real pdftotext integration check."""
    lines = ["Championnat de France Eau Libre 2025", title, "Villefranche-sur-Mer",
             "FFESSM", "Classement", "Nom", "Profondeur"]
    lines.extend(f"{j+1} PERSON {j+1}" for j in range(count))
    stream = b"\n".join(f"BT /F1 12 Tf 40 {720 - n*16} Td ({line}) Tj ET".encode("latin-1")
                        for n, line in enumerate(lines))
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
               b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
    body = b"%PDF-1.4\n"
    offsets = [0]
    for number, obj in enumerate(objects, 1):
        offsets.append(len(body))
        body += f"{number} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(body)
    body += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
    for offset in offsets[1:]:
        body += f"{offset:010d} 00000 n \n".encode()
    body += f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return body


def fixture(tmp_path):
    index = tmp_path / "index.html"
    links = ('<a href="https://example.org/day1.pdf">Jour 1 - Vendredi 27 juin 2025</a>\n'
             '<a href="https://example.org/day2.pdf">Jour 2 - Samedi 28 juin 2025</a>\n'
             + "\n".join(f'<a href="{url.removeprefix("https://apnee.ffessm.fr")}">{label}</a>'
                         for url, label in zip(URLS, LABELS)))
    index.write_text("<html><body>Résultats 2025 " + links + "</body></html>")
    inventory = tmp_path / "inventory.json"
    sources = []
    snapshot = tmp_path / "snapshot.sqlite"
    with sqlite3.connect(snapshot) as db:
        db.execute("create table records (source_name text, collection text, kind text, "
                   "record_path text, source_object_id text, citation_json text, "
                   "raw_fields_json text, raw_json text, category text, discipline text, "
                   "review_status text)")
        for i, (url, title) in enumerate(zip(URLS, HEADINGS)):
            source = tmp_path / f"source-{i}.pdf"
            source.write_bytes(pdf(title, COUNTS[i]))
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            sources.append({"id": "sha256:" + digest, "sha256": digest,
                            "bytes": source.stat().st_size, "source_path": str(source),
                            "classification": "eligible", "content_type": "application/pdf",
                            "receipt": {"final_url": url, "discovery_url": "https://apnee.ffessm.fr/resultats-2025",
                                        "retrieved_at": "2026-09-26T18:00:00Z"}})
            for j in range(COUNTS[i]):
                printed = f"{j+1} PERSON {j+1}"
                raw = {"id": f"source-position:{i}-{j}", "coordinates": {"page": 1, "line": j + 8,
                       "column-start": 1, "column-end": len(printed)+1},
                       "locator": f"page 1 line {j+8} column start 1 column end {len(printed)+1}",
                       "raw_evidence": {"line": printed},
                       "source_lines": [{"page": 1, "line": j+8, "text": printed}],
                       "raw_fields": {"rank": str(j + 1), "source-name": f"Person {i}"},
                       "status": "parsed", "unresolved_reason": "owner-review-required",
                       "observation_refs": [{"job_id": f"job-{i}-{j}", "ordinal": j,
                                              "parser_version": "fixture/1",
                                              "citation": f"page 1 line {j+8} column start 1 column end {len(printed)+1}"}]}
                db.execute("insert into records values (?,?,?,?,?,?,?,?,?,?,?)",
                           ("baseline", "positions", "candidate_position", f"positions[{i}-{j}]",
                            "sha256:" + digest, json.dumps(raw["locator"]),
                            json.dumps(raw["raw_fields"]), json.dumps(raw),
                            CATEGORIES[i], DISCIPLINES[i], "unreviewed"))
    inventory.write_text(json.dumps({"sources": sources}))
    return index, inventory, snapshot


def run(tmp_path, index, inventory, snapshot):
    output = tmp_path / "packet.json"
    result = subprocess.run([sys.executable, str(SCRIPT), "--index", str(index),
                             "--inventory", str(inventory), "--snapshot", str(snapshot),
                             "--output", str(output)], capture_output=True, text=True)
    return result, output


def test_packet_cites_nine_originals_and_snapshot_rows(tmp_path):
    index, inventory, snapshot = fixture(tmp_path)
    result, output = run(tmp_path, index, inventory, snapshot)
    assert result.returncode == 0, result.stderr
    first = output.read_bytes()
    packet = json.loads(first)
    assert packet["schema"] == "ffessm-2025-rankings/v1"
    assert packet["counts"] == {"source_objects": 9, "source_positions": 56,
                                 "existing_observation_versions": 56,
                                 "confirmed_distinct_attempts": None}
    assert [s["index_label"] for s in packet["sources"]] == LABELS
    assert [s["url"] for s in packet["sources"]] == URLS
    assert [s["heading"] for s in packet["sources"]] == HEADINGS
    assert [s["discipline"] for s in packet["sources"]] == DISCIPLINES
    assert [s["source_positions"] for s in packet["sources"]] == COUNTS
    assert all(s["source_type"] == "aggregate_ranking" for s in packet["sources"])
    assert all(s["event_year"] == 2025 and s["event_date"] is None for s in packet["sources"])
    assert len(packet["relationship_candidates"]) == 18
    assert {(r["ranking_url"], r["daily_url"]) for r in packet["relationship_candidates"]} == {
        (url, daily) for url in URLS for daily in ("https://example.org/day1.pdf", "https://example.org/day2.pdf")}
    assert all(r["status"] == "unknown" and r["same_attempt"] is None
               and r["source_equivalence"] is None for r in packet["relationship_candidates"])
    assert packet["positions"][0]["citation"] == "page 1 line 8 column start 1 column end 11"
    assert packet["positions"][0]["raw_fields"]["rank"] == "1"
    assert packet["positions"][0]["printed_line"] == "1 PERSON 1"
    assert packet["positions"][0]["source_lines"][0]["text"] == "1 PERSON 1"
    assert packet["positions"][0]["category"] == "Femmes"
    assert packet["positions"][0]["discipline"] == "CWT-MONO"
    assert packet["positions"][0]["status"] == "parsed"
    assert packet["positions"][0]["unresolved_reason"] == "owner-review-required"
    assert packet["positions"][0]["observation_refs"][0]["job_id"] == "job-0-0"
    assert packet["positions"][0]["event_date"] is None
    assert os.stat(output).st_mode & 0o777 == 0o600
    result, _ = run(tmp_path, index, inventory, snapshot)
    assert result.returncode == 0 and output.read_bytes() == first


def test_rejects_changed_original_and_unlinked_source(tmp_path):
    index, inventory, snapshot = fixture(tmp_path)
    data = json.loads(inventory.read_text())
    Path(data["sources"][0]["source_path"]).write_bytes(b"changed")
    result, output = run(tmp_path, index, inventory, snapshot)
    assert result.returncode != 0 and "hash" in result.stderr.lower()
    assert not output.exists()
    Path(data["sources"][0]["source_path"]).write_bytes(pdf(HEADINGS[0], 2))
    index.write_text(index.read_text().replace(LABELS[0], "Wrong label"))
    result, output = run(tmp_path, index, inventory, snapshot)
    assert result.returncode != 0 and "label" in result.stderr.lower()
    assert not output.exists()


def test_rejects_snapshot_with_missing_printed_position(tmp_path):
    index, inventory, snapshot = fixture(tmp_path)
    with sqlite3.connect(snapshot) as db:
        db.execute("delete from records where record_path='positions[1-7]'")
    result, output = run(tmp_path, index, inventory, snapshot)
    assert result.returncode != 0 and "position count" in result.stderr.lower()
    assert not output.exists()


def test_rejects_snapshot_line_or_observation_citation_conflict(tmp_path):
    index, inventory, snapshot = fixture(tmp_path)
    with sqlite3.connect(snapshot) as db:
        record = db.execute("select raw_json from records where record_path='positions[0-0]'").fetchone()[0]
        raw = json.loads(record)
        raw["raw_evidence"]["line"] = "1 CHANGED"
        db.execute("update records set raw_json=? where record_path='positions[0-0]'", (json.dumps(raw),))
    result, output = run(tmp_path, index, inventory, snapshot)
    assert result.returncode != 0 and "printed line" in result.stderr.lower()
    assert not output.exists()
    with sqlite3.connect(snapshot) as db:
        raw["raw_evidence"]["line"] = "1 PERSON 1"
        raw["observation_refs"][0]["citation"] = "page 1 line 9 column start 1 column end 11"
        db.execute("update records set raw_json=? where record_path='positions[0-0]'", (json.dumps(raw),))
    result, output = run(tmp_path, index, inventory, snapshot)
    assert result.returncode != 0 and "observation citation" in result.stderr.lower()
    assert not output.exists()
