"""Paced Playwright capture for official source pages and their subrequests.

Pass a Playwright Page to capture_page. Every HTTP(S) route, including browser
navigation, API traffic and downloads, acquires the same host lease as HTTP
acquisition. The caller retains returned response bytes and rendered DOM in a
private archive; this module does not register observations.
"""

from dataclasses import dataclass
from datetime import date
from hashlib import sha256
import json
import random
import re
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, build_opener

from acquire_source import _safe_url
from source_inventory import find_reusable_source, legacy_archive_gaps
from source_acquisition import AcquisitionClient, _NoRedirect, _retry_after


_CHALLENGE = (b"captcha", b"verify you are human", b"cloudflare challenge",
              b"access denied", b"bot detection", b"rate limit exceeded",
              b"checking your browser", b"attention required", b"unusual traffic")
_RETRYABLE = (429, 500, 502, 503, 504)
_REDIRECT = (301, 302, 303, 307, 308)
_SENSITIVE_NAME = re.compile(r"token|api[_-]?key|secret|password|session|auth|credential|signature|jwt|storage", re.I)
_SENSITIVE_VALUE = re.compile(r"\b(?:token|api[_-]?key|secret|password|session|auth|credential|signature|jwt)\s*[:=]|\bbearer\s+", re.I)
_SPORT_SESSION_NAMES = {"session", "session_name"}


class BrowserAcquisitionError(Exception):
    def __init__(self, host, reason, status=None, events=()):
        self.host, self.reason, self.status = host, reason, status
        self.events = tuple(events)
        super().__init__(f"browser acquisition {reason} at {host}")


@dataclass(frozen=True)
class BrowserResponse:
    url: str
    host: str
    status: int
    content_type: str | None
    body: bytes
    sha256: str


@dataclass(frozen=True)
class BrowserCapture:
    entry_url: str
    dom: str
    responses: tuple[BrowserResponse, ...]
    redirects: tuple[tuple[str, str, int], ...]
    reuses: tuple[dict, ...] = ()
    events: tuple[dict, ...] = ()
    final_url: str | None = None
    selected_state: dict | None = None


def reject_sensitive_metadata(value):
    """Reject credential-shaped user metadata before it enters capture receipts."""
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("sensitive metadata key")
            if key.lower() in _SPORT_SESSION_NAMES:
                if not isinstance(item, str) or not 1 <= len(item) <= 160 or re.search(r"[\r\n=&?]", item):
                    raise ValueError("invalid sporting session")
            elif _SENSITIVE_NAME.search(key):
                raise ValueError("sensitive metadata key")
            reject_sensitive_metadata(item)
    elif isinstance(value, list):
        for item in value:
            reject_sensitive_metadata(item)
    elif isinstance(value, str):
        if _SENSITIVE_VALUE.search(value):
            raise ValueError("sensitive metadata value")
        if value.startswith(("http://", "https://")):
            _safe_url(value)


def _validate_selection(selection):
    if selection is None:
        return
    if not isinstance(selection, dict) or selection.get("schema") != "browser-selection/v1":
        raise ValueError("selection requires browser-selection/v1")
    reject_sensitive_metadata(selection)
    try:
        if not isinstance(selection["selected_date"], str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", selection["selected_date"]):
            raise ValueError
        date.fromisoformat(selection["selected_date"])
        state = selection["selected_state"]
        if not isinstance(state["selector"], str) or not state["selector"] or not isinstance(state["text"], str) or not state["text"]:
            raise ValueError
        if not isinstance(selection["result_selector"], str) or not selection["result_selector"]:
            raise ValueError
        if type(selection["min_results"]) is not int or selection["min_results"] < 1:
            raise ValueError
        if not isinstance(selection["response_contains"], str) or not selection["response_contains"]:
            raise ValueError
        actions = selection["actions"]
        if not isinstance(actions, list) or not actions:
            raise ValueError
        for action in actions:
            if not isinstance(action["selector"], str) or not action["selector"]:
                raise ValueError
            if action["type"] == "select_option":
                if not isinstance(action["value"], str) or not action["value"]:
                    raise ValueError
            elif action["type"] != "click":
                raise ValueError
        filters = selection.get("verified_filters", [])
        if not isinstance(filters, list):
            raise ValueError
        names = set()
        for item in filters:
            if (not isinstance(item["name"], str) or not item["name"] or
                    not isinstance(item["selector"], str) or not item["selector"] or
                    not isinstance(item["text"], str) or not item["text"] or item["name"] in names):
                raise ValueError
            names.add(item["name"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("invalid browser selection") from error


def _validate(host, status, content_type, body, resource_type):
    if status in _REDIRECT:
        return False
    if status != 200:
        raise BrowserAcquisitionError(host, "publisher_restriction" if status in (401, 403, 451) else "http_status", status)
    mime = (content_type or "").split(";", 1)[0].strip().lower()
    sample = body[:8192].lower()
    if any(marker in sample for marker in _CHALLENGE):
        raise BrowserAcquisitionError(host, "access_challenge", status)
    if resource_type in ("stylesheet", "script", "image", "font", "media"):
        return False
    if mime == "application/pdf":
        if not body.startswith(b"%PDF-"):
            raise BrowserAcquisitionError(host, "wrong_response_signature", status)
    elif mime == "application/json" or mime.endswith("+json"):
        try:
            json.loads(body)
        except (ValueError, UnicodeDecodeError):
            raise BrowserAcquisitionError(host, "wrong_response_signature", status) from None
    elif mime in ("text/html", "application/xhtml+xml"):
        if b"<html" not in sample and b"<!doctype html" not in sample:
            raise BrowserAcquisitionError(host, "wrong_response_signature", status)
    else:
        raise BrowserAcquisitionError(host, "unsupported_response_type", status)
    return True


def capture_page(page, url, client: AcquisitionClient, *, opener=None,
                 archive_roots=(), source_context=None, refresh=False, selection=None):
    """Capture a Page through the shared lease; return exact response bytes and DOM.

    Each browser request is replayed through a bounded HTTP reader. Playwright's
    route.fetch buffers the response before its body can be checked. Redirects
    are fulfilled to the browser so each destination enters a new host lease.
    """
    _validate_selection(selection)
    reject_sensitive_metadata(source_context or {})
    reject_sensitive_metadata(url)
    failures = []
    responses = []
    redirects = []
    reuses = []
    events = []
    redirect_count = 0
    opener = opener or build_opener(_NoRedirect)

    def handle(route):
        nonlocal redirect_count
        request_url = route.request.url
        parts = urlsplit(request_url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
            failure = BrowserAcquisitionError(parts.hostname or "invalid-host", "invalid_url")
            failures.append(failure)
            route.abort()
            return
        host = parts.hostname.lower()
        try:
            reject_sensitive_metadata(request_url)
            _safe_url(request_url)
        except ValueError:
            failures.append(BrowserAcquisitionError(host, "unsafe_url"))
            route.abort()
            return
        policy = client.policy_for(host)
        browser_request = route.request
        method = getattr(browser_request, "method", "GET")
        request_headers = browser_request.all_headers() if hasattr(browser_request, "all_headers") else {}
        session_bound = any(re.search(r"cookie|authorization|token|api-key|session|credential", key, re.I)
                            for key in request_headers)
        if method == "GET" and not refresh and not session_bound:
            for representation in ("html", "json", "pdf"):
                try:
                    reusable = find_reusable_source(archive_roots, request_url, representation,
                                                    source_context or {})
                except ValueError:
                    failures.append(BrowserAcquisitionError(host, "archive_inventory_failed"))
                    route.abort()
                    return
                if reusable is None:
                    continue
                body = reusable["body"]
                if len(body) > policy.max_bytes:
                    failures.append(BrowserAcquisitionError(host, "size_limit"))
                    route.abort()
                    return
                content_type = reusable["record"]["content_type"]
                try:
                    if _validate(host, 200, content_type, body,
                                 getattr(browser_request, "resource_type", "document")):
                        responses.append(BrowserResponse(request_url, host, 200, content_type,
                                                         body, sha256(body).hexdigest()))
                except BrowserAcquisitionError as failure:
                    failures.append(failure)
                    route.abort()
                    return
                reuses.append({"url": request_url, "sha256": sha256(body).hexdigest(),
                               "provenance_path": str(reusable["provenance_path"]),
                               "original_retrieved_at": reusable["record"]["retrieved_at"],
                               "freshness": "not_checked"})
                events.append({"host": host, "attempt": 0, "wait": 0,
                               "reason": "archive_reuse", "outcome": "complete"})
                route.fulfill(status=200, headers={"content-type": content_type}, body=body)
                return
        if method not in ("GET", "POST"):
            failures.append(BrowserAcquisitionError(host, "unsupported_method"))
            route.abort()
            return
        headers = {key: value for key, value in request_headers.items()
                   if key.lower() not in ("host", "content-length", "transfer-encoding",
                                          "accept-encoding", "connection", "proxy-authorization")}
        data = (getattr(browser_request, "post_data_buffer", None) or b"") if method == "POST" else None
        # Replaying a POST after an uncertain response can duplicate a publisher action.
        attempt_limit = 1 if method == "POST" else policy.max_attempts
        for attempt in range(1, attempt_limit + 1):
            try:
                with client.lease(request_url) as waited:
                    if waited:
                        events.append({"host": host, "attempt": attempt, "wait": waited,
                                       "reason": "host_pacing", "outcome": "waited"})
                    request = Request(request_url, data=data, headers=headers, method=method)
                    try:
                        response = opener.open(request, timeout=policy.timeout)
                    except HTTPError as error:
                        response = error
                    with response:
                        status = response.status
                        response_headers = response.headers
                        if status in _RETRYABLE:
                            retry_after = _retry_after(response_headers.get("retry-after"))
                            continue_retry = True
                            events.append({"host": host, "attempt": attempt, "wait": 0,
                                           "reason": "transient_status", "outcome": str(status)})
                        else:
                            continue_retry = False
                        if status in _REDIRECT:
                            location = response_headers.get("location")
                            target = urljoin(request_url, location or "")
                            target_parts = urlsplit(target)
                            redirect_count += 1
                            if (not location or redirect_count > policy.max_redirects or
                                    target_parts.scheme not in ("http", "https") or
                                    not target_parts.hostname or target_parts.username or target_parts.password):
                                raise BrowserAcquisitionError(host, "redirect_limit", status)
                            if parts.scheme == "https" and target_parts.scheme != "https":
                                raise BrowserAcquisitionError(host, "insecure_redirect", status)
                            try:
                                reject_sensitive_metadata(target)
                                _safe_url(target)
                            except ValueError:
                                raise BrowserAcquisitionError(host, "unsafe_redirect", status) from None
                            redirects.append((request_url, target, status))
                            body = b""
                        elif continue_retry:
                            body = b""
                        else:
                            if status != 200:
                                _validate(host, status, response_headers.get("content-type"), b"",
                                          getattr(browser_request, "resource_type", "document"))
                            encoding = response_headers.get("content-encoding", "identity").lower()
                            if encoding != "identity":
                                raise BrowserAcquisitionError(host, "unsupported_content_encoding", status)
                            declared_length = response_headers.get("content-length")
                            if declared_length is not None:
                                try:
                                    expected_length = int(declared_length)
                                    if expected_length < 0:
                                        raise ValueError
                                    if expected_length > policy.max_bytes:
                                        raise BrowserAcquisitionError(host, "size_limit", status)
                                except ValueError:
                                    raise BrowserAcquisitionError(host, "invalid_content_length", status) from None
                            body = response.read(policy.max_bytes + 1)
                            if len(body) > policy.max_bytes:
                                raise BrowserAcquisitionError(host, "size_limit", status)
                            if declared_length is not None and len(body) != expected_length:
                                raise BrowserAcquisitionError(host, "incomplete_response", status)
                            is_source = _validate(host, status, response_headers.get("content-type"), body,
                                                  getattr(browser_request, "resource_type", "document"))
                            if status == 200 and is_source:
                                responses.append(BrowserResponse(request_url, host, status, response_headers.get("content-type"),
                                                                 body, sha256(body).hexdigest()))
                        if not continue_retry:
                            events.append({"host": host, "attempt": attempt, "wait": 0,
                                           "reason": "redirect" if status in _REDIRECT else "success",
                                           "outcome": "follow" if status in _REDIRECT else "complete"})
                            safe_headers = {key: value for key, value in response_headers.items()
                                            if key.lower() not in ("content-length", "transfer-encoding", "content-encoding")}
                            route.fulfill(status=status, headers=safe_headers, body=body)
                            return
                if attempt == attempt_limit:
                    raise BrowserAcquisitionError(host, "attempts_exhausted", status)
                delay = retry_after if retry_after is not None else min(policy.max_delay, 2 ** (attempt - 1) + random.random())
                if delay > policy.max_delay:
                    raise BrowserAcquisitionError(host, "retry_after_exceeds_max_delay", status)
                events.append({"host": host, "attempt": attempt, "wait": delay,
                               "reason": "retry_after" if retry_after is not None else "backoff",
                               "outcome": "waited"})
                time.sleep(delay)
            except BrowserAcquisitionError as failure:
                events.append({"host": host, "attempt": attempt, "wait": 0,
                               "reason": failure.reason, "outcome": "failed"})
                failures.append(failure)
                route.abort()
                return
            except Exception:
                events.append({"host": host, "attempt": attempt, "wait": 0,
                               "reason": "network_error", "outcome": "failed"})
                if attempt == attempt_limit:
                    failures.append(BrowserAcquisitionError(host, "network_error"))
                    route.abort()
                    return
                time.sleep(min(policy.max_delay, 2 ** (attempt - 1) + random.random()))

    page.route("**/*", handle)
    try:
        if archive_roots:
            # This verifies all objects in configured legacy archives before
            # navigation; old records lack response facts needed for reuse.
            try:
                if any(not Path(root).is_dir() or Path(root).is_symlink() for root in archive_roots):
                    raise ValueError("archive root unavailable")
                legacy_archive_gaps(archive_roots, url, "html", source_context or {})
            except ValueError:
                raise BrowserAcquisitionError(_safe_url(url), "archive_inventory_failed") from None
        try:
            page.goto(url, wait_until="networkidle")
        except Exception:
            if failures:
                failures[0].events = tuple(events)
                raise failures[0] from None
            raise
        if failures:
            failures[0].events = tuple(events)
            raise failures[0]
        selected_state = None
        if selection is not None:
            for action in selection["actions"]:
                if action["type"] == "select_option":
                    page.select_option(action["selector"], action["value"])
                else:
                    page.click(action["selector"])
            if hasattr(page, "wait_for_load_state"):
                page.wait_for_load_state("networkidle")
            if failures:
                failures[0].events = tuple(events)
                raise failures[0]
            state = selection["selected_state"]
            actual_text = page.locator(state["selector"]).inner_text().strip()
            count = page.locator(selection["result_selector"]).count()
            filters = {}
            for item in selection.get("verified_filters", []):
                observed = page.locator(item["selector"]).inner_text().strip()
                if observed != item["text"]:
                    raise BrowserAcquisitionError(urlsplit(url).hostname, "selected_filter_mismatch", events=events)
                filters[item["name"]] = item["text"]
            if actual_text != state["text"] or count < selection["min_results"]:
                raise BrowserAcquisitionError(urlsplit(url).hostname, "incomplete_rendered_results", events=events)
            marker = selection["response_contains"].encode("utf-8")
            if not any(marker in response.body for response in responses):
                raise BrowserAcquisitionError(urlsplit(url).hostname, "missing_result_response", events=events)
            selected_state = {"selected_date": selection["selected_date"],
                              "selected_text": state["text"], "filters": filters,
                              "result_count": count}
        dom = page.content()
        if len(dom.encode("utf-8")) > client.policy_for(urlsplit(url).hostname).max_bytes:
            raise BrowserAcquisitionError(urlsplit(url).hostname, "size_limit", events=events)
        final_url = getattr(page, "url", url)
        try:
            reject_sensitive_metadata(final_url)
            _safe_url(final_url)
        except ValueError:
            raise BrowserAcquisitionError(urlsplit(url).hostname, "unsafe_url", events=events) from None
        return BrowserCapture(url, dom, tuple(responses), tuple(redirects),
                              tuple(reuses), tuple(events), final_url, selected_state)
    finally:
        page.unroute("**/*", handle)
