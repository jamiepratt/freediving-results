"""Paced Playwright capture for official source pages and their subrequests.

Pass a Playwright Page to capture_page. Every HTTP(S) route, including browser
navigation, API traffic and downloads, acquires the same host lease as HTTP
acquisition. The caller retains returned response bytes and rendered DOM in a
private archive; this module does not register observations.
"""

from dataclasses import dataclass
from hashlib import sha256
import json
import time
from urllib.parse import urljoin, urlsplit

from acquire_source import _safe_url
from source_acquisition import AcquisitionClient, _retry_after


_CHALLENGE = (b"captcha", b"verify you are human", b"cloudflare challenge",
              b"access denied", b"bot detection", b"rate limit exceeded",
              b"checking your browser", b"attention required", b"unusual traffic")
_RETRYABLE = (429, 500, 502, 503, 504)
_REDIRECT = (301, 302, 303, 307, 308)


class BrowserAcquisitionError(Exception):
    def __init__(self, host, reason, status=None):
        self.host, self.reason, self.status = host, reason, status
        super().__init__(f"browser acquisition {reason} at {host}")


@dataclass(frozen=True)
class BrowserResponse:
    host: str
    status: int
    content_type: str | None
    body: bytes
    sha256: str


@dataclass(frozen=True)
class BrowserCapture:
    dom: str
    responses: tuple[BrowserResponse, ...]


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


def capture_page(page, url, client: AcquisitionClient):
    """Capture a Page through the shared lease; return exact response bytes and DOM.

    Playwright route.fetch uses max_redirects=0 so each redirect destination
    enters a fresh route and lease. Exceptions are saved because Playwright may
    swallow route-handler exceptions and surface only a failed navigation.
    """
    failures = []
    responses = []
    redirect_count = 0

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
            _safe_url(request_url)
        except ValueError:
            failures.append(BrowserAcquisitionError(host, "unsafe_url"))
            route.abort()
            return
        policy = client.policy_for(host)
        for attempt in range(1, policy.max_attempts + 1):
            try:
                with client.lease(request_url):
                    response = route.fetch(timeout=int(policy.timeout * 1000), max_redirects=0)
                    status = response.status
                    if status in _RETRYABLE:
                        retry_after = _retry_after(response.headers.get("retry-after"))
                    else:
                        if status in _REDIRECT:
                            location = response.headers.get("location")
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
                                _safe_url(target)
                            except ValueError:
                                raise BrowserAcquisitionError(host, "unsafe_redirect", status) from None
                        declared_length = response.headers.get("content-length")
                        if declared_length is not None:
                            try:
                                if int(declared_length) > policy.max_bytes:
                                    raise BrowserAcquisitionError(host, "size_limit", status)
                            except ValueError:
                                raise BrowserAcquisitionError(host, "invalid_content_length", status) from None
                        body = response.body()
                        if len(body) > policy.max_bytes:
                            raise BrowserAcquisitionError(host, "size_limit", status)
                        is_source = _validate(host, status, response.headers.get("content-type"), body,
                                              getattr(route.request, "resource_type", "document"))
                        if status == 200 and is_source:
                            responses.append(BrowserResponse(host, status, response.headers.get("content-type"),
                                                             body, sha256(body).hexdigest()))
                        route.fulfill(response=response)
                        return
                if attempt == policy.max_attempts:
                    raise BrowserAcquisitionError(host, "attempts_exhausted", status)
                delay = retry_after if retry_after is not None else min(policy.max_delay, 2 ** (attempt - 1))
                if delay > policy.max_delay:
                    raise BrowserAcquisitionError(host, "retry_after_exceeds_max_delay", status)
                time.sleep(delay)
            except BrowserAcquisitionError as failure:
                failures.append(failure)
                route.abort()
                return
            except Exception:
                if attempt == policy.max_attempts:
                    failures.append(BrowserAcquisitionError(host, "network_error"))
                    route.abort()
                    return
                time.sleep(min(policy.max_delay, 2 ** (attempt - 1)))

    page.route("**/*", handle)
    try:
        try:
            page.goto(url, wait_until="networkidle")
        except Exception:
            if failures:
                raise failures[0] from None
            raise
        if failures:
            raise failures[0]
        return BrowserCapture(page.content(), tuple(responses))
    finally:
        page.unroute("**/*", handle)
