"""Durable, bounded discovery frontier for declared publisher routes.

This records leads and route checks, not ingestion or worldwide coverage. A lead
may have several discovery edges but one retrieval identity. Evidence fields are
opaque citations; they never imply athlete identity or ranking semantics.
"""

from hashlib import sha256
import json
import os
from pathlib import Path
import tempfile


SCHEMA = "result-discovery-frontier/v1"
DISPOSITIONS = {"acquired", "gap", "unsupported", "out_of_scope"}


def _digest(value):
    return sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _evidence(value):
    if not isinstance(value, dict) or not value:
        raise ValueError("a nonempty evidence object is required")
    return value


class DiscoveryFrontier:
    """JSON checkpoint with explicit route and lead completion gates."""

    def __init__(self, path, data, request_budget):
        self.path = Path(path)
        self.data = data
        if request_budget is not None and (not isinstance(request_budget, int) or request_budget < 0):
            raise ValueError("request_budget must be a nonnegative integer")
        self.request_budget = request_budget
        self.session_requests = 0

    @property
    def routes(self):
        return self.data["routes"]

    @property
    def leads(self):
        return self.data["leads"]

    @classmethod
    def load(cls, path, scope, request_budget=None):
        required = {"federation", "years", "source_families", "routes", "cutoff"}
        if not isinstance(scope, dict) or not required <= scope.keys():
            raise ValueError("scope needs federation, years, source_families, routes and cutoff")
        if not all(isinstance(scope[key], list) and scope[key] for key in
                   ("years", "source_families", "routes")):
            raise ValueError("scope years, families and routes must be nonempty lists")
        path = Path(path)
        if path.exists():
            data = json.loads(path.read_text())
            if data.get("schema") != SCHEMA or data.get("scope") != scope:
                raise ValueError("checkpoint schema or declared scope changed")
        else:
            data = {"schema": SCHEMA, "scope": scope,
                    "routes": {route: {"status": "unchecked"} for route in scope["routes"]},
                    "leads": {}, "requests": 0}
        return cls(path, data, request_budget)

    def add_lead(self, url, *, route, year, source_family, evidence, metadata=None):
        if route not in self.data["routes"]:
            raise ValueError("route outside declared scope")
        _evidence(evidence)
        if metadata is not None and not isinstance(metadata, dict):
            raise ValueError("metadata must be an object")
        # A selected day or session can reuse a URL while naming distinct views.
        # Discovery routes may overlap, but acquisition identity includes context.
        key = _digest([url, year, source_family, (metadata or {}).get("context", {})])
        lead = self.data["leads"].setdefault(key, {
            "url": url, "year": year, "source_family": source_family,
            "edges": [], "disposition": None, "metadata": metadata or {}})
        edge = {"route": route, "evidence": evidence}
        if edge not in lead["edges"]:
            lead["edges"].append(edge)
        if (year not in self.data["scope"]["years"] or
                source_family not in self.data["scope"]["source_families"]):
            lead["disposition"] = {"status": "out_of_scope", "evidence": {
                "reason": "year or source family outside declared scope"}}
        self.save()
        return key

    def mark_route(self, route, status="checked", *, evidence):
        if route not in self.data["routes"]:
            raise ValueError("route outside declared scope")
        if status not in {"checked", "gap"}:
            raise ValueError("route status must be checked or gap")
        self.data["routes"][route] = {"status": status, "evidence": _evidence(evidence)}
        self.save()

    def record_disposition(self, key, status, *, evidence):
        if key not in self.data["leads"]:
            raise KeyError(key)
        if status not in DISPOSITIONS:
            raise ValueError("unsupported lead disposition")
        self.data["leads"][key]["disposition"] = {
            "status": status, "evidence": _evidence(evidence)}
        self.save()

    def spend_request(self, count=1):
        """Reserve a request before issuing it; false means checkpoint and resume."""
        if not isinstance(count, int) or count < 1:
            raise ValueError("count must be a positive integer")
        if self.request_budget is not None and self.session_requests + count > self.request_budget:
            return False
        self.session_requests += count
        self.data["requests"] += count
        self.save()
        return True

    def summary(self):
        unchecked_routes = sorted(route for route, item in self.data["routes"].items()
                                  if item["status"] == "unchecked")
        unchecked_leads = sorted(key for key, item in self.data["leads"].items()
                                 if item["disposition"] is None)
        gaps = [{"kind": "route", "key": route, "evidence": item["evidence"]}
                for route, item in self.data["routes"].items() if item["status"] == "gap"]
        gaps.extend({"kind": "lead", "key": key,
                     "status": item["disposition"]["status"],
                     "evidence": item["disposition"]["evidence"]}
                    for key, item in self.data["leads"].items()
                    if item["disposition"] and item["disposition"]["status"] in {"gap", "unsupported"})
        return {"state": "checkpoint" if unchecked_routes or unchecked_leads else "complete",
                "unchecked_routes": unchecked_routes, "unchecked_leads": unchecked_leads,
                "gaps": gaps, "leads": len(self.data["leads"]),
                "requests": self.data["requests"], "session_requests": self.session_requests}

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        payload = (json.dumps(self.data, sort_keys=True, indent=2) + "\n").encode()
        with tempfile.NamedTemporaryFile(dir=self.path.parent, delete=False) as output:
            temporary = Path(output.name)
            os.chmod(temporary, 0o600)
            output.write(payload)
        os.replace(temporary, self.path)
