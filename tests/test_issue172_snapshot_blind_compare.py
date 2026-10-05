import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/issue172_snapshot_blind_compare.py"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fixture(tmp_path, *, duplicate=False):
    db = tmp_path / "snapshot.sqlite"
    con = sqlite3.connect(db)
    con.execute("create table source_metadata(source_name text, metadata_json text)")
    con.execute("create table records(source_name text, collection text, page integer, raw_json text)")
    source = digest(b"retained pdf")
    con.execute("insert into source_metadata values (?, ?)", ("friday", json.dumps({"schema": "friday-night-visual-evidence/v1", "source_sha256": source})))
    row = {"row": 1, "fields": {"Cognome": "Test", "Nome": "Person", "Punteggio": "12"}, "citation": {"page": 3}}
    con.execute("insert into records values (?, ?, ?, ?)", ("friday", "pages.rows", 3, json.dumps(row)))
    if duplicate:
        con.execute("insert into records values (?, ?, ?, ?)", ("friday", "pages.rows", 3, json.dumps(row)))
    con.commit()
    con.close()
    blind = {"source_sha256": source, "source_pages": 3, "athlete_rows": [{"page": 3, "source_position": "p03:r1", "surname": "Test", "given_name": "Person", "score": "12"}], "club_aggregate_rows": []}
    blind_path = tmp_path / "blind.json"
    blind_path.write_text(json.dumps(blind))
    return db, blind_path, source


def run(tmp_path, *, duplicate=False, source_override=None):
    db, blind, source = fixture(tmp_path, duplicate=duplicate)
    return subprocess.run([sys.executable, str(SCRIPT), "--source-kind", "friday",
                           "--snapshot", str(db), "--snapshot-sha256", digest(db.read_bytes()),
                           "--blind", str(blind), "--blind-sha256", digest(blind.read_bytes()),
                           "--expected-source-sha256", source_override or source], capture_output=True, text=True)


def test_matching_pinned_snapshot_and_blind_rows(tmp_path):
    result = run(tmp_path)
    assert result.returncode == 0, result.stderr
    report = json.loads(result.stdout)
    assert report["counts"]["athlete_positions"] == 1
    assert report["counts"]["disagreements"] == 0


def test_duplicate_source_position_rejected(tmp_path):
    result = run(tmp_path, duplicate=True)
    assert result.returncode != 0
    assert "duplicate source position" in result.stderr


def test_source_hash_mismatch_rejected(tmp_path):
    result = run(tmp_path, source_override=digest(b"different"))
    assert result.returncode != 0
    assert "source SHA256 mismatch" in result.stderr


def test_changed_blind_bytes_rejected(tmp_path):
    db, blind, source = fixture(tmp_path)
    pinned = digest(blind.read_bytes())
    blind.write_text(blind.read_text() + " ")
    result = subprocess.run([sys.executable, str(SCRIPT), "--source-kind", "friday",
                             "--snapshot", str(db), "--snapshot-sha256", digest(db.read_bytes()),
                             "--blind", str(blind), "--blind-sha256", pinned,
                             "--expected-source-sha256", source], capture_output=True, text=True)
    assert result.returncode != 0
    assert "blind transcript SHA256 mismatch" in result.stderr
