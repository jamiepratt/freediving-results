"""Versioned refresh decisions for explicitly configured discovery routes.

This module plans publisher checks; archive verification alone never advances
``last_publisher_check``. The current HTTP acquisition client does not issue
conditional requests, so a 304 is recorded only when another trusted caller
supplies that observed response.
"""

from datetime import date, datetime


# Starting cadence, not an assertion of publisher update schedules. Callers may
# supply a complete versioned override after observing a source family's pace.
DEFAULT_POLICY = {
    "schema": "discovery-refresh-policy/v1",
    "recent_window_days": 365,
    "recent_interval_days": 7,
    "provisional_interval_days": 1,
    "stable_interval_days": 90,
    "max_attempts": 3,
    "retry_base_seconds": 2,
}


def _day(value):
    if isinstance(value, date) and not isinstance(value, datetime):
        return value
    if not isinstance(value, str):
        raise ValueError("date must be ISO YYYY-MM-DD")
    parsed = date.fromisoformat(value)
    if parsed.isoformat() != value:
        raise ValueError("date must be ISO YYYY-MM-DD")
    return parsed


def _timestamp(value):
    if not isinstance(value, str):
        raise ValueError("publisher check must be an ISO timestamp")
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("publisher check must include a timezone")
    return parsed


def _policy(value):
    value = DEFAULT_POLICY if value is None else value
    if not isinstance(value, dict) or value.get("schema") != "discovery-refresh-policy/v1":
        raise ValueError("unsupported refresh policy")
    for name in ("recent_window_days", "recent_interval_days", "provisional_interval_days",
                 "stable_interval_days", "max_attempts", "retry_base_seconds"):
        if not isinstance(value.get(name), int) or isinstance(value[name], bool) or value[name] < 0:
            raise ValueError("refresh policy needs nonnegative integer " + name)
    if min(value["recent_interval_days"], value["provisional_interval_days"],
           value["stable_interval_days"], value["max_attempts"]) == 0:
        raise ValueError("refresh intervals and attempt budget must be positive")
    if value["provisional_interval_days"] > value["recent_interval_days"] or \
            value["recent_interval_days"] > value["stable_interval_days"]:
        raise ValueError("provisional, recent and stable intervals must be ordered")
    return value


def plan_refresh(kind, as_of, publication_date=None, provisional=False,
                 last_publisher_check=None, policy=None):
    """Decide whether a publisher request is due in a *new* discovery run.

    ``publication_date`` is a cited publisher date, never inferred from an event
    date. Unknown dates receive recent cadence as a conservative default.
    Within-run deduplication belongs to the run frontier, not this calendar rule.
    """
    policy = _policy(policy)
    today = _day(as_of)
    if kind == "index":
        return {"due": True, "refresh": True, "publisher_check_due": True,
                "reason": "index_each_run", "interval_days": 0,
                "policy_schema": policy["schema"]}
    if kind != "result":
        raise ValueError("refresh kind must be index or result")
    if publication_date is not None:
        published = _day(publication_date)
        if published > today:
            raise ValueError("publication date is in the future")
    else:
        published = None
    if provisional:
        interval, category = policy["provisional_interval_days"], "provisional"
    elif published is None or (today - published).days <= policy["recent_window_days"]:
        interval, category = policy["recent_interval_days"], "recent_or_undated"
    else:
        interval, category = policy["stable_interval_days"], "stable"
    if last_publisher_check is None:
        due, reason = True, "never_checked"
    else:
        checked = _timestamp(last_publisher_check) if "T" in str(last_publisher_check) else _day(last_publisher_check)
        checked_day = checked.date() if isinstance(checked, datetime) else checked
        if checked_day > today:
            raise ValueError("last publisher check is in the future")
        due = (today - checked_day).days >= interval
        reason = category + ("_due" if due else "_not_due")
    return {"due": due, "refresh": due, "publisher_check_due": due,
            "reason": reason, "interval_days": interval,
            "policy_schema": policy["schema"]}


def record_publisher_result(previous, *, checked_at, status, sha256=None, gap_reason=None):
    """Append an observed source version or explicit access gap.

    The caller must have actual publisher response evidence. A reused archive
    object must not be passed here as a 200 response.
    """
    _timestamp(checked_at)
    if not isinstance(status, int) or isinstance(status, bool):
        raise ValueError("HTTP status is required")
    previous = previous or {}
    if previous and previous.get("schema") != "discovery-refresh-state/v1":
        raise ValueError("unsupported refresh state")
    versions = list(previous.get("versions", []))
    if status == 200:
        if not isinstance(sha256, str) or len(sha256) != 64 or any(c not in "0123456789abcdef" for c in sha256):
            raise ValueError("successful publisher response needs a SHA-256 digest")
        if sha256 == (versions[-1] if versions else None):
            outcome = "unchanged_bytes"
        else:
            outcome = "changed_bytes" if versions else "first_version"
            versions.append(sha256)
        gap = None
    elif status == 304:
        if sha256 is not None or not versions:
            raise ValueError("304 requires a prior retained version and no response body")
        outcome, gap = "not_modified", None
    else:
        if sha256 is not None or not gap_reason:
            raise ValueError("failed publisher check needs an explicit gap reason")
        outcome = "gap"
        gap = {"status": status, "reason": gap_reason, "checked_at": checked_at}
    return {"schema": "discovery-refresh-state/v1", "versions": versions,
            "latest_sha256": versions[-1] if versions else None,
            "last_publisher_check": checked_at, "outcome": outcome, "gap": gap}


def retry_delay(attempt, policy=None):
    """Seconds before numbered attempt, or None after the attempt budget."""
    policy = _policy(policy)
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
        raise ValueError("attempt must be a positive integer")
    if attempt > policy["max_attempts"]:
        return None
    return 0 if attempt == 1 else policy["retry_base_seconds"] * 2 ** (attempt - 2)
