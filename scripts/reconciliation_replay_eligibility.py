"""Structural preflight for a private retained-corpus decision replay.

Passing this preflight is necessary, not sufficient: the caller must verify the
bound stores and source citations before any decision or provider dispatch.
"""

from collections.abc import Mapping
import re


_HEX64 = re.compile(r"[0-9a-f]{64}\Z")
_API_FIELDS = {
    "identity": ("observation-id", "source-name", "parse-status", "citation"),
    "attempt": ("sources", "positions", "observation-versions"),
    "flow": ("decisions", "config", "policy"),
}


def _digest(value):
    return isinstance(value, str) and _HEX64.fullmatch(value) is not None


def _store_revision(value):
    if not isinstance(value, Mapping):
        return False
    revision = value.get("revision")
    return (isinstance(revision, int) and not isinstance(revision, bool) and revision >= 0
            or isinstance(revision, str) and bool(revision.strip())) and _digest(value.get("sha256"))


def _api_mapping(value):
    if not isinstance(value, Mapping):
        return False
    return all(isinstance(value.get(family), Mapping) and all(
        isinstance(value[family].get(field), str) and bool(value[family][field].strip())
        for field in fields) for family, fields in _API_FIELDS.items())


def replay_blockers(snapshot, binding):
    """Return stable blockers for missing revisions or explicit API field mapping.

    `snapshot` contains independently verified manifest and SQLite digests.
    `binding` is a separate frozen, private input, never inferred from rows.
    """
    if not isinstance(snapshot, Mapping) or not all(
        _digest(snapshot.get(key)) for key in ("manifest_sha256", "sqlite_sha256")
    ):
        raise ValueError("verified snapshot digests required")
    binding = binding if isinstance(binding, Mapping) else {}
    blockers = []
    if binding and binding.get("snapshot") != snapshot:
        blockers.append("snapshot-binding-mismatch")
    if not _store_revision(binding.get("observation_store")):
        blockers.append("missing-observation-store-revision")
    if not _store_revision(binding.get("decision_store")):
        blockers.append("missing-decision-store-revision")
    if not _api_mapping(binding.get("decision_api_mapping")):
        blockers.append("missing-decision-api-mapping")
    return blockers
