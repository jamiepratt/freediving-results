"""Cited FFESSM daily and ranking correspondences remain auditable."""

import copy
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from scripts.ffessm_2025_daily_relationships import reconcile


def fixture():
    daily_source = "sha256:" + "a" * 64
    rank_source = "sha256:" + "b" * 64
    first = {"id": daily_source + ":position:1", "source_id": daily_source,
             "citation": "page 1 line 7", "surname": "REBILLOUT", "given_name": "Caroline",
             "discipline": "CWT-MONO", "announced_depth_m": 75, "realized_depth_m": 75,
             "depth_penalty": 0, "plate_fault_penalty": None,
             "total_points": 75, "card": "Blanc", "federation": "FFESSM"}
    unmatched = {**first, "id": daily_source + ":position:2", "citation": "page 1 line 8",
                 "surname": "EXTRA", "given_name": "Athlete", "federation": "CMAS"}
    ranking = {"position_id": "source-position:1", "source_object_id": rank_source,
               "citation": "page 1 line 9 column start 1 column end 119",
               "discipline": "CWT-MONO", "raw_fields": {
                   "family-name": "REBILLOUT", "given-name": "Caroline",
                   "announced-depth": "75 m", "realized-depth": "75",
                   "depth-penalty": "0", "plate-penalty": "0",
                   "final-points": "75", "card": "Blanc"}}
    return ({"schema": "ffessm-2025-daily/v1", "observations": [first, unmatched],
             "sources": [{"id": daily_source}]},
            {"schema": "ffessm-2025-rankings/v1", "positions": [ranking],
             "sources": [{"id": rank_source}]})


def test_shared_printed_fields_keep_blank_plate_and_attempt_uncertainty():
    daily, rankings = fixture()
    packet = reconcile(daily, rankings)
    assert packet["counts"] == {"ranking_positions": 1, "daily_positions": 2,
                                "shared_printed_field_correspondences": 1,
                                "unmatched_daily_positions": 1,
                                "confirmed_distinct_attempts": None}
    edge = packet["relationships"][0]
    assert edge["kind"] == "shared_printed_fields"
    assert edge["same_attempt"] is None
    assert edge["daily"]["citation"] == "page 1 line 7"
    assert edge["ranking"]["citation"] == "page 1 line 9 column start 1 column end 119"
    assert edge["plate_evidence"] == "daily_blank_ranking_zero"
    assert edge["field_correspondences"]["announced_depth_m"]["value"] == 75
    assert packet["unmatched_daily"][0]["id"].endswith(":position:2")


def test_duplicate_exact_daily_rows_reject_ambiguous_ranking_match():
    daily, rankings = fixture()
    duplicate = copy.deepcopy(daily["observations"][0])
    duplicate["id"] = duplicate["source_id"] + ":position:3"
    duplicate["citation"] = "page 1 line 10"
    daily["observations"].append(duplicate)
    with pytest.raises(ValueError, match="ambiguous"):
        reconcile(daily, rankings)


def test_cli_checks_input_hash_and_writes_private_deterministic_packet(tmp_path):
    daily, rankings = fixture()
    daily_path, ranking_path, output = (tmp_path / "daily.json", tmp_path / "ranking.json",
                                        tmp_path / "relationships.json")
    daily_path.write_text(json.dumps(daily), encoding="utf-8")
    ranking_path.write_text(json.dumps(rankings), encoding="utf-8")
    daily_sha = hashlib.sha256(daily_path.read_bytes()).hexdigest()
    ranking_sha = hashlib.sha256(ranking_path.read_bytes()).hexdigest()
    command = [sys.executable, str(Path(__file__).resolve().parents[1] / "scripts"
                                    / "ffessm_2025_daily_relationships.py"),
               "--daily", str(daily_path), "--rankings", str(ranking_path),
               "--expected-daily-sha", daily_sha,
               "--expected-ranking-sha", ranking_sha, "--output", str(output)]
    run = subprocess.run(command, capture_output=True, text=True)
    assert run.returncode == 0, run.stderr
    first = output.read_bytes()
    assert os.stat(output).st_mode & 0o777 == 0o600
    assert json.loads(first)["inputs"] == {"daily_sha256": daily_sha,
                                            "ranking_sha256": ranking_sha}
    run = subprocess.run(command, capture_output=True, text=True)
    assert run.returncode == 0 and output.read_bytes() == first
    output.unlink()
    command[command.index(daily_sha)] = "0" * 64
    run = subprocess.run(command, capture_output=True, text=True)
    assert run.returncode != 0 and "SHA-256 mismatch" in run.stderr
    assert not output.exists()
