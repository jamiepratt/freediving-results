"""The Apnea Academy file packet replays source aggregate positions."""

import hashlib
import json
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "apnea_academy_file_reconcile.py"


def workbook(path):
    cells = ['<row r="1"><c r="B1" t="inlineStr"><is><t>GIA 2025</t></is></c></row>']
    for n in range(7, 47):
        cells.append(f'<row r="{n}"><c r="B{n}" t="inlineStr"><is><t>Club {n}</t></is></c>'
                     f'<c r="N{n}"><v>{n}</v></c></row>')
    cells.append('<row r="47"><c r="N47"><v>0</v></c></row>')
    prefix = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
    with ZipFile(path, "w") as book:
        book.writestr("xl/workbook.xml", f'<workbook xmlns="{prefix}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Classifiche  società 2024" sheetId="1" r:id="rId1"/></sheets></workbook>')
        book.writestr("xl/_rels/workbook.xml.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>')
        book.writestr("xl/worksheets/sheet1.xml", f'<worksheet xmlns="{prefix}"><dimension ref="B1:N47"/><sheetData>{"".join(cells)}</sheetData></worksheet>')


def pdf(path):
    lines = ["GIRO D'ITALIA IN APNEA - TROFEO SAN MAURO", "Pomigliano d'Arco 1 marzo 2026",
             "Classifica squadre", "SQUADRA ATLETI PUNTI DIN PUNTI STA TOT TAPPA"]
    lines += [f"{n} Club {n} {n} 1.000,00 2,00 1.002,00" for n in range(1, 11)]
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
    body += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets[1:])
    body += f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    path.write_bytes(body)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(tmp_path, team, individual, squadre, prior):
    output = tmp_path / "out.json"
    command = [sys.executable, str(SCRIPT), "--team-workbook", str(team),
               "--team-sha256", sha(team), "--individual-workbook", str(individual),
               "--individual-sha256", sha(individual), "--prior-snapshot-manifest", str(prior),
               "--prior-manifest-sha256", sha(prior), "--squadre-pdf", str(squadre),
               "--squadre-sha256", sha(squadre), "--output", str(output)]
    return subprocess.run(command, capture_output=True, text=True), output


def test_replays_aggregate_rows_and_existing_individual_equivalence(tmp_path):
    team = tmp_path / "team.xlsx"
    individual = tmp_path / "individual.xlsx"
    squadre = tmp_path / "squadre.pdf"
    prior = tmp_path / "prior.json"
    workbook(team)
    individual.write_bytes(b"verified individual original")
    pdf(squadre)
    prior.write_text(json.dumps({"schema": "unified-evidence-snapshot/v1",
                                 "inputs": {"gia": {"source_schema": "gia-2025-individual-workbook-census/v1",
                                                    "source_sha256": sha(individual), "sha256": "a" * 64,
                                                    "collections": {"sheets.rows": 898}}}}))
    result, output = run(tmp_path, team, individual, squadre, prior)
    assert result.returncode == 0, result.stderr
    packet = json.loads(output.read_text())
    assert packet["schema"] == "apnea-academy-file-reconciliation/v1"
    assert packet["counts"] == {"gia_team_standings_rows": 40, "gia_team_placeholder_rows": 1,
                                  "san_mauro_team_rows": 10, "confirmed_distinct_attempts": None}
    assert packet["gia_team"]["rows"][0]["cells"]["B7"]["citation"] == "Classifiche  società 2024!B7"
    assert packet["gia_team"]["placeholder"]["cells"]["N47"]["value"] == 0
    assert "2024" in packet["gia_team"]["ambiguities"][0]
    assert packet["san_mauro"]["rows"][0]["fields"]["din_points"] == "1.000,00"
    assert packet["san_mauro"]["rows"][0]["citation"] == "page 1 line 5"
    assert packet["san_mauro"]["event_date"] == "2026-03-01"
    assert packet["individual_equivalence"]["same_original_sha256"] is True
    first = output.read_bytes()
    result, _ = run(tmp_path, team, individual, squadre, prior)
    assert result.returncode == 0 and output.read_bytes() == first


def test_rejects_changed_individual_original(tmp_path):
    team = tmp_path / "team.xlsx"
    individual = tmp_path / "individual.xlsx"
    squadre = tmp_path / "squadre.pdf"
    prior = tmp_path / "prior.json"
    workbook(team)
    individual.write_bytes(b"changed original")
    pdf(squadre)
    prior.write_text(json.dumps({"schema": "unified-evidence-snapshot/v1",
                                 "inputs": {"gia": {"source_schema": "gia-2025-individual-workbook-census/v1",
                                                    "source_sha256": "0" * 64, "sha256": "a" * 64,
                                                    "collections": {"sheets.rows": 898}}}}))
    result, output = run(tmp_path, team, individual, squadre, prior)
    assert result.returncode != 0
    assert "individual" in result.stderr.lower()
    assert not output.exists()
