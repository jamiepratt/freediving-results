"""Daily FFESSM source rows replay from exact extracted PDF lines."""

import os
from pathlib import Path

import pytest

from scripts.ffessm_2025_daily import build, parse_daily_text


def test_day_one_cites_and_parses_printed_results():
    extracted = ("  Résultats Eau Libre J1\n"
                 "  Championnat de France 2025 - Villefranche sur mer\n"
                 "  Compétiteurs Sexe Nationalité Fédération Profondeur Annoncée Discipline Profondeur Réalisée\n"
                 "GOMBERT         Charles-Antoine Homme Française   FFESSM          85 m       FIM             85 m          0                     0     Rouge    DQ syncope surface\n"
                 "PROVENZANI      Kevin           Homme Française   FFESSM          85 m       FIM             79 m          6            1       72     Jaune    Annonce non atteinte\n\f")
    rows = parse_daily_text(extracted, 1)
    assert len(rows) == 2
    assert rows[0]["citation"] == "page 1 line 4"
    assert rows[0]["raw_line"] == extracted.splitlines()[3]
    assert rows[0]["position"] == 1
    assert rows[0]["athlete"] == "GOMBERT Charles-Antoine"
    assert rows[0]["announced_depth_m"] == 85
    assert rows[0]["realized_depth_m"] == 85
    assert rows[0]["depth_penalty"] == 0
    assert rows[0]["plate_fault_penalty"] is None
    assert rows[0]["total_points"] == 0
    assert rows[0]["card"] == "Rouge"
    assert rows[0]["comments"] == "DQ syncope surface"
    assert rows[0]["status"] == "disqualified"
    assert rows[1]["citation"] == "page 1 line 5"
    assert rows[1]["plate_fault_penalty"] == 1
    assert rows[1]["total_points"] == 72
    assert rows[1]["status"] == "penalized"


def test_day_two_retains_negative_points_and_rejects_unparsed_athlete_lines():
    title = ("Résultats Eau Libre J2\n"
             "Championnat de France 2025 - Villefranche-sur-mer\n")
    row = ("SIMONIS Marine Femme Belge FFESSM 57 m CNF 25 m 32 1 -8 Jaune "
           "Annonce non atteinte")
    rows = parse_daily_text(title + row + "\n\f", 2)
    assert rows[0]["total_points"] == -8
    assert rows[0]["depth_penalty"] == 32
    assert rows[0]["plate_fault_penalty"] == 1
    assert rows[0]["discipline"] == "CNF"
    with pytest.raises(ValueError, match="unparsed athlete line: page 1 line 3"):
        parse_daily_text(title + row.replace("Jaune", "Unknown") + "\n\f", 2)


def test_archived_originals_replay_all_printed_positions_when_present():
    if not os.environ.get("FFESSM_2025_DAILY_CORPUS"):
        pytest.skip("set FFESSM_2025_DAILY_CORPUS for private original replay")
    root = Path(os.environ["FFESSM_2025_DAILY_CORPUS"])
    packet = build(root / "raw/day1.pdf", root / "raw/day2.pdf",
                   root / "receipt-manifest.json")
    assert packet["counts"] == {"source_objects": 2, "printed_positions": 71,
                                "parser_observations": 71,
                                "confirmed_distinct_attempts": None}
    assert [source["printed_positions"] for source in packet["sources"]] == [39, 32]
    assert [row["citation"] for row in (packet["observations"][0],
                                          packet["observations"][38],
                                          packet["observations"][39],
                                          packet["observations"][-1])] == [
        "page 1 line 8", "page 1 line 46", "page 1 line 7", "page 1 line 38"]
    assert len({row["id"] for row in packet["observations"]}) == 71
