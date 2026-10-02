"""A configured discovery pass must keep its unfinished work visible."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))


def config():
    source = {"id": "aida-events", "publisher": "AIDA", "index_url": "https://official.example/events",
              "index_representation": "json", "candidate_representation": "html",
              "allowed_host": "official.example", "context": {}, "year": 2026,
              "source_family": "results"}
    return {"schema": "scoped-result-discovery/v1", "as_of": "2026-10-02", "cutoff": "2025-01-01",
            "scope": {"federation": "AIDA", "years": [2025, 2026],
                      "source_families": ["results"], "routes": ["aida-events"],
                      "cutoff": "2026-10-02"}, "sources": [source]}


def test_budget_checkpoint_resumes_without_repeating_index_or_lead(tmp_path, monkeypatch):
    import discovery_pass

    cfg = tmp_path / "config.json"
    cfg.write_text(json.dumps(config()))
    calls = []

    def fake_acquire(url, representation, archive, *, client, context, refresh=False):
        calls.append((url, refresh))
        body = (json.dumps({"results": [{"url": "/r1", "date": "2026-06-01", "representation": "html"},
                                         {"url": "/r2", "date": "2026-06-02", "representation": "html"}]}).encode()
                if url.endswith("/events") else b"<html>result</html>")
        digest = __import__("hashlib").sha256(body).hexdigest()
        archive.mkdir(parents=True, exist_ok=True)
        source = archive / (digest + "." + representation)
        source.write_bytes(body)
        receipt = archive / ("receipt-" + str(len(calls)) + ".json")
        receipt.write_text(json.dumps({"final_url": url, "redirect_chain": [url],
                                       "retrieved_at": "2026-10-02T12:00:00Z"}))
        return {"source_path": str(source), "provenance_path": str(receipt),
                "run_path": "run.json", "sha256": digest, "status": "acquired"}

    monkeypatch.setattr(discovery_pass, "acquire", fake_acquire)
    first = discovery_pass.run_pass(cfg, tmp_path / "output", request_budget=2, client=object())
    assert first["state"] == "checkpoint"
    assert len(first["unchecked_leads"]) == 1
    second = discovery_pass.run_pass(cfg, tmp_path / "output", request_budget=3, client=object())
    assert second["state"] == "complete"
    assert calls == [("https://official.example/events", True),
                     ("https://official.example/r1", True),
                     ("https://official.example/r2", True)]


def test_unsupported_source_family_is_explicit(tmp_path):
    import discovery_pass

    payload = config()
    payload["sources"][0]["source_family"] = "image-sheet"
    payload["scope"]["source_families"] = ["image-sheet"]
    cfg = tmp_path / "config.json"
    cfg.write_text(json.dumps(payload))
    result = discovery_pass.run_pass(cfg, tmp_path / "output", request_budget=2, client=object())
    assert result["state"] == "complete"
    assert result["gaps"]
    assert result["requests"] == 0


def test_overlapping_routes_keep_two_edges_and_fetch_one_result(tmp_path, monkeypatch):
    import discovery_pass

    payload = config()
    second = dict(payload["sources"][0], id="aida-calendar")
    payload["sources"].append(second)
    payload["scope"]["routes"].append("aida-calendar")
    cfg = tmp_path / "config.json"
    cfg.write_text(json.dumps(payload))
    calls = []

    def fake_acquire(url, representation, archive, *, client, context, refresh=False):
        calls.append(url)
        body = (b'{"results":[{"url":"/result","date":"2026-06-01","representation":"html"}]}'
                if url.endswith(("/events", "/calendar")) else b"<html>result</html>")
        digest = __import__("hashlib").sha256(body).hexdigest()
        archive.mkdir(parents=True, exist_ok=True)
        source = archive / (digest + "." + representation)
        source.write_bytes(body)
        receipt = archive / ("receipt-" + str(len(calls)) + ".json")
        receipt.write_text(json.dumps({"final_url": url, "redirect_chain": [url],
                                       "retrieved_at": "2026-10-02T12:00:00Z"}))
        return {"source_path": str(source), "provenance_path": str(receipt),
                "sha256": digest, "status": "acquired"}

    monkeypatch.setattr(discovery_pass, "acquire", fake_acquire)
    checkpoint = discovery_pass.run_pass(cfg, tmp_path / "output", request_budget=1, client=object())
    assert checkpoint["state"] == "checkpoint"
    result = discovery_pass.run_pass(cfg, tmp_path / "output", request_budget=1, client=object())
    assert result["state"] == "complete"
    assert calls == ["https://official.example/events", "https://official.example/result"]
    frontier = next((tmp_path / "output").glob("frontier-*.json"))
    leads = json.loads(frontier.read_text())["leads"]
    assert len(leads) == 1
    assert {edge["route"] for edge in next(iter(leads.values()))["edges"]} == {
        "aida-events", "aida-calendar"}


def test_forbidden_result_is_a_completed_discovery_gap(tmp_path, monkeypatch):
    import discovery_pass
    from acquire_source import SourceRejected

    cfg = tmp_path / "config.json"
    cfg.write_text(json.dumps(config()))
    calls = []

    def fake_acquire(url, representation, archive, *, client, context, refresh=False):
        calls.append(url)
        archive.mkdir(parents=True, exist_ok=True)
        if url.endswith("/r1"):
            gap = archive / "gap-403.json"
            gap.write_text(json.dumps({"status": 403, "retrieved_at": "2026-10-02T12:00:00Z"}))
            raise SourceRejected("permanent_status", gap)
        body = (b'{"results":[{"url":"/r1","date":"2026-06-01","representation":"html"}]}'
                if url.endswith("/events") else b"<html>result</html>")
        digest = __import__("hashlib").sha256(body).hexdigest()
        source = archive / (digest + "." + representation)
        source.write_bytes(body)
        receipt = archive / ("receipt-" + str(len(calls)) + ".json")
        receipt.write_text(json.dumps({"final_url": url, "redirect_chain": [url],
                                       "retrieved_at": "2026-10-02T12:00:00Z"}))
        return {"source_path": str(source), "provenance_path": str(receipt),
                "sha256": digest, "status": "acquired"}

    monkeypatch.setattr(discovery_pass, "acquire", fake_acquire)
    result = discovery_pass.run_pass(cfg, tmp_path / "output", client=object())
    assert result["state"] == "complete"
    assert result["gaps"][0]["evidence"]["status"] == 403
    assert result["requests"] == 2


def test_old_cited_publication_reuses_archive_across_runs(tmp_path, monkeypatch):
    import discovery_pass

    payload = config()
    payload["cutoff"] = "2020-01-01"
    payload["scope"]["years"] = [2020]
    payload["sources"][0]["year"] = 2020
    cfg = tmp_path / "config.json"
    calls = []

    def fake_acquire(url, representation, archive, *, client, context, refresh=False):
        calls.append((url, refresh))
        body = (b'{"results":[{"url":"/old","date":"2020-06-01",'
                b'"publication_date":"2020-07-01","representation":"html"}]}'
                if url.endswith("/events") else b"<html>old result</html>")
        digest = __import__("hashlib").sha256(body).hexdigest()
        archive.mkdir(parents=True, exist_ok=True)
        source = archive / (digest + "." + representation)
        source.write_bytes(body)
        receipt = archive / ("receipt-" + str(len(calls)) + ".json")
        receipt.write_text(json.dumps({"final_url": url, "redirect_chain": [url],
                                       "retrieved_at": "2026-10-01T12:00:00Z"}))
        return {"source_path": str(source), "provenance_path": str(receipt),
                "sha256": digest, "status": "acquired" if refresh else "reused"}

    monkeypatch.setattr(discovery_pass, "acquire", fake_acquire)
    payload["as_of"] = payload["scope"]["cutoff"] = "2026-10-01"
    cfg.write_text(json.dumps(payload))
    first = discovery_pass.run_pass(cfg, tmp_path / "output", client=object())
    monkeypatch.setattr(discovery_pass, "find_reusable_source", lambda *args: {"verified": True})
    payload["as_of"] = payload["scope"]["cutoff"] = "2026-10-11"
    cfg.write_text(json.dumps(payload))
    second = discovery_pass.run_pass(cfg, tmp_path / "output", client=object())
    assert first["state"] == second["state"] == "complete"
    assert calls == [("https://official.example/events", True),
                     ("https://official.example/old", True),
                     ("https://official.example/events", True),
                     ("https://official.example/old", False)]
    assert second["cache_reuse"] == 1


def test_undated_index_links_remain_route_coverage_gap(tmp_path, monkeypatch):
    import discovery_pass

    payload = config()
    payload["sources"][0]["index_representation"] = "html"
    cfg = tmp_path / "config.json"
    cfg.write_text(json.dumps(payload))

    def fake_acquire(url, representation, archive, *, client, context, refresh=False):
        body = (b'<html><a href="/dated" data-date="2026-06-01">result</a>'
                b'<a href="/unknown">other</a></html>' if url.endswith("/events")
                else b"<html>result</html>")
        digest = __import__("hashlib").sha256(body).hexdigest()
        archive.mkdir(parents=True, exist_ok=True)
        source = archive / (digest + "." + representation)
        source.write_bytes(body)
        receipt = archive / (digest + ".json")
        receipt.write_text(json.dumps({"final_url": url, "redirect_chain": [url],
                                       "retrieved_at": "2026-10-02T12:00:00Z"}))
        return {"source_path": str(source), "provenance_path": str(receipt),
                "sha256": digest, "status": "acquired"}

    monkeypatch.setattr(discovery_pass, "acquire", fake_acquire)
    result = discovery_pass.run_pass(cfg, tmp_path / "output", client=object())
    assert result["state"] == "complete"
    assert result["leads"] == 1
    assert result["gaps"][0]["evidence"]["reason"] == "undated_links_not_evaluated"
