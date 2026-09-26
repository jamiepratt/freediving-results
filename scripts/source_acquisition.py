"""Bounded HTTP acquisition primitives for publisher source bytes.

Share one AcquisitionClient among workers. Callers retain successful bytes only
after fetch returns and record failures separately as coverage gaps.
"""

from dataclasses import dataclass
from contextlib import contextmanager, closing
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from http.client import IncompleteRead
import random
import re
import os
from pathlib import Path
import socket
import sqlite3
import threading
import time
from uuid import uuid4
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


@dataclass(frozen=True)
class Policy:
    concurrency: int = 2
    min_interval: float = 1.0
    timeout: float = 20.0
    max_attempts: int = 3
    max_delay: float = 30.0
    max_redirects: int = 5
    max_bytes: int = 50 * 1024 * 1024

    def __post_init__(self):
        if self.concurrency < 1 or self.max_attempts < 1 or self.max_redirects < 0 or self.max_bytes < 1:
            raise ValueError("invalid acquisition policy count")
        if self.min_interval < 0 or self.timeout <= 0 or self.max_delay < 0:
            raise ValueError("invalid acquisition policy duration")


CMAS_POLICY = Policy(concurrency=1, min_interval=3.0, timeout=20.0,
                     max_attempts=2, max_delay=30.0)


@dataclass(frozen=True)
class Event:
    host: str
    attempt: int
    wait: float
    reason: str
    outcome: str


@dataclass(frozen=True)
class Result:
    body: bytes
    status: int
    final_url: str
    redirects: tuple[str, ...]
    content_type: str | None
    attempts: int
    events: tuple[Event, ...]


class AcquisitionError(Exception):
    def __init__(self, host, attempts, reason, status=None, events=()):
        self.host, self.attempts, self.reason, self.status = host, attempts, reason, status
        self.events = tuple(events)
        super().__init__(f"acquisition {reason} at {host} after {attempts} attempt(s)")


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


class _LeaseStore:
    """SQLite transactions serialize starts across clients and worker processes."""

    def __init__(self, path):
        self.path = Path(path).expanduser()
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Create privately before SQLite can open the file with process umask.
        descriptor = os.open(self.path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(descriptor)
        with closing(self._connect()) as db:
            db.execute("CREATE TABLE IF NOT EXISTS hosts (host TEXT PRIMARY KEY, next_start REAL NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS leases (token TEXT PRIMARY KEY, host TEXT NOT NULL, pid INTEGER NOT NULL, expires REAL NOT NULL)")
            # Version 1 stored wall-clock deadlines. They cannot safely be
            # compared with monotonic time, so discard only pacing state.
            if db.execute("PRAGMA user_version").fetchone()[0] < 2:
                db.execute("DELETE FROM hosts")
                db.execute("DELETE FROM leases")
                db.execute("PRAGMA user_version = 2")

    def _connect(self):
        return sqlite3.connect(self.path, timeout=10, isolation_level=None)

    @staticmethod
    def _alive(pid):
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True

    def enter(self, host, policy):
        start = time.monotonic()
        token = uuid4().hex
        lifetime = max(60.0, policy.timeout * 3)
        while True:
            with closing(self._connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                now = time.monotonic()
                db.execute("DELETE FROM leases WHERE expires <= ?", (now,))
                for stale_token, pid in db.execute("SELECT token, pid FROM leases WHERE host = ?", (host,)):
                    if not self._alive(pid):
                        db.execute("DELETE FROM leases WHERE token = ?", (stale_token,))
                count = db.execute("SELECT COUNT(*) FROM leases WHERE host = ?", (host,)).fetchone()[0]
                row = db.execute("SELECT next_start FROM hosts WHERE host = ?", (host,)).fetchone()
                # An older client can still write a wall-clock deadline after
                # this database migrates. Ignore only unmistakable Unix-time
                # values; keep normal monotonic deadlines across processes.
                if row and row[0] > 1_000_000_000 and now < 1_000_000_000:
                    db.execute("DELETE FROM hosts WHERE host = ?", (host,))
                    row = None
                delay = max(0.0, (row[0] if row else 0.0) - now)
                if count < policy.concurrency and delay == 0:
                    db.execute("INSERT INTO leases VALUES (?, ?, ?, ?)", (token, host, os.getpid(), now + lifetime))
                    db.execute("INSERT INTO hosts VALUES (?, ?) ON CONFLICT(host) DO UPDATE SET next_start = excluded.next_start",
                               (host, now + policy.min_interval))
                    db.commit()
                    return token, time.monotonic() - start, lifetime
                db.commit()
            time.sleep(min(0.1, delay) if delay else 0.05)

    def renew(self, token, lifetime):
        with closing(self._connect()) as db:
            db.execute("UPDATE leases SET expires = ? WHERE token = ?", (time.monotonic() + lifetime, token))

    def leave(self, token):
        with closing(self._connect()) as db:
            db.execute("DELETE FROM leases WHERE token = ?", (token,))


def _host(url):
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        raise ValueError("source URL must be HTTP(S) without embedded credentials")
    return parts.hostname.lower()


def _retry_after(value):
    if not value:
        return None
    value = value.strip()
    if re.fullmatch(r"[0-9]+", value):
        return float(int(value))
    try:
        date = parsedate_to_datetime(value)
        if date.tzinfo is None:
            date = date.replace(tzinfo=timezone.utc)
        return max(0.0, (date - datetime.now(timezone.utc)).total_seconds())
    except (TypeError, ValueError, OverflowError):
        return None


class AcquisitionClient:
    def __init__(self, default_policy=None, host_policies=None, event_sink=None,
                 lease_path=None, state_path=None):
        self.default_policy = default_policy or Policy()
        self.host_policies = {host.lower(): policy for host, policy in (host_policies or {}).items()}
        self.event_sink = event_sink
        if lease_path is not None and state_path is not None and Path(lease_path) != Path(state_path):
            raise ValueError("lease_path and state_path disagree")
        default_state = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "freediving-results" / "acquisition.sqlite3"
        self._leases = _LeaseStore(lease_path or state_path or default_state)
        self._opener = build_opener(_NoRedirect)

    def policy_for(self, host):
        if host in self.host_policies:
            return self.host_policies[host]
        if host == "cmas.org" or host.endswith(".cmas.org"):
            return CMAS_POLICY
        return self.default_policy

    @contextmanager
    def lease(self, url):
        """Reserve a publisher host before one HTTP or browser request starts."""
        host = _host(url)
        token, waited, lifetime = self._leases.enter(host, self.policy_for(host))
        stop = threading.Event()

        def heartbeat():
            while not stop.wait(min(10.0, lifetime / 3)):
                self._leases.renew(token, lifetime)

        thread = threading.Thread(target=heartbeat, daemon=True)
        thread.start()
        try:
            yield waited
        finally:
            stop.set()
            thread.join()
            self._leases.leave(token)

    def fetch(self, url, *, url_validator=None):
        origin = _host(url)
        events = []

        def record(host, attempt, wait, reason, outcome):
            event = Event(host, attempt, wait, reason, outcome)
            events.append(event)
            if self.event_sink:
                self.event_sink(event)

        for attempt in range(1, self.policy_for(origin).max_attempts + 1):
            current = url
            redirects = []
            status = None
            while True:
                status = None
                host = _host(current)
                if url_validator is not None:
                    url_validator(current)
                policy = self.policy_for(host)
                try:
                    with self.lease(current) as waited:
                        if waited:
                            record(host, attempt, waited, "host_pacing", "waited")
                        try:
                            response = self._opener.open(Request(current, method="GET"), timeout=policy.timeout)
                        except HTTPError as error:
                            response = error
                        with response:
                            status = response.status
                            headers = response.headers
                            if status in (301, 302, 303, 307, 308):
                                location = headers.get("Location")
                                if not location or len(redirects) >= policy.max_redirects:
                                    raise AcquisitionError(host, attempt, "redirect_limit", status, events)
                                target = urljoin(current, location)
                                _host(target)
                                if urlsplit(current).scheme == "https" and urlsplit(target).scheme != "https":
                                    raise AcquisitionError(host, attempt, "insecure_redirect", status, events)
                                redirects.append(target)
                                record(host, attempt, 0, "redirect", "follow")
                            elif status == 200:
                                body = response.read(policy.max_bytes + 1)
                                if len(body) > policy.max_bytes:
                                    raise AcquisitionError(host, attempt, "size_limit", status, events)
                                length = headers.get("Content-Length")
                                if length is not None:
                                    try:
                                        expected = int(length)
                                    except ValueError:
                                        raise AcquisitionError(host, attempt, "invalid_content_length", status, events) from None
                                    if len(body) != expected:
                                        raise IncompleteRead(body, expected)
                                record(host, attempt, 0, "success", "complete")
                                return Result(body, status, current, tuple(redirects),
                                              headers.get("Content-Type"), attempt, tuple(events))
                            else:
                                retryable = status in (429, 500, 502, 503, 504)
                                reason = "transient_status" if retryable else "permanent_status"
                                record(host, attempt, 0, reason, str(status))
                                if not retryable:
                                    raise AcquisitionError(host, attempt, reason, status, events)
                                retry_delay = _retry_after(headers.get("Retry-After"))
                except (URLError, TimeoutError, socket.timeout, ConnectionError, IncompleteRead, OSError) as error:
                    record(host, attempt, 0, "network_error", type(error).__name__)
                    retry_delay = None
                if status in (301, 302, 303, 307, 308):
                    current = redirects[-1]
                    continue
                break
            if attempt == self.policy_for(origin).max_attempts:
                raise AcquisitionError(host, attempt, "attempts_exhausted", status, events)
            delay = retry_delay if retry_delay is not None else min(policy.max_delay, 2 ** (attempt - 1) + random.random())
            if delay > policy.max_delay:
                raise AcquisitionError(host, attempt, "retry_after_exceeds_max_delay", status, events)
            record(host, attempt, delay, "retry_after" if retry_delay is not None else "backoff", "waited")
            time.sleep(delay)
