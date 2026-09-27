"""Capture selected FIPSAS EventON calendar months with source-byte receipts.

The input page must be a fresh, public FIPSAS apnea page containing EventON's
shortcode configuration and nonce. The nonce is sent to EventON only and is
never written to a capture or receipt.
"""

import argparse
from datetime import datetime, timezone
from hashlib import sha256
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import stat
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo

from source_acquisition import AcquisitionClient


MONTHS_IT = (
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)
SCHEMA = "fipsas-eventon-month/v1"


class CalendarError(ValueError):
    pass


class _CalendarData(HTMLParser):
    def __init__(self):
        super().__init__()
        self.shortcodes = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if "evo_cal_data" in values.get("class", "").split() and "data-sc" in values:
            self.shortcodes.append(values["data-sc"])


def _page_config(source):
    page = source.decode("utf-8")
    parser = _CalendarData()
    parser.feed(page)
    shortcodes = []
    for value in parser.shortcodes:
        try:
            shortcode = json.loads(value)
        except json.JSONDecodeError:
            continue
        if isinstance(shortcode, dict) and "event_type" in shortcode:
            shortcodes.append(shortcode)
    if len(shortcodes) != 1:
        raise CalendarError("expected one EventON competition shortcode")
    match = re.search(r"\bevo_general_params\s*=\s*", page)
    if not match:
        raise CalendarError("EventON nonce configuration missing")
    try:
        params, _ = json.JSONDecoder().raw_decode(page[match.end():].lstrip())
    except json.JSONDecodeError as error:
        raise CalendarError("EventON nonce configuration malformed") from error
    nonce = params.get("n") if isinstance(params, dict) else None
    if not isinstance(nonce, str) or not nonce:
        raise CalendarError("EventON nonce missing")
    return shortcodes[0], nonce


def _month_range(year, month):
    if not (1900 <= year <= 2100 and 1 <= month <= 12):
        raise CalendarError("invalid year or month")
    zone = ZoneInfo("Europe/Rome")
    start = datetime(year, month, 1, tzinfo=zone)
    if month == 12:
        after = datetime(year + 1, 1, 1, tzinfo=zone)
    else:
        after = datetime(year, month + 1, 1, tzinfo=zone)
    return int(start.timestamp()), int(after.timestamp()) - 1


def _form(shortcode, nonce, year, month):
    start, end = _month_range(year, month)
    selected = {str(key): str(value) for key, value in shortcode.items()}
    selected.update(fixed_year=str(year), fixed_month=str(month), fixed_day="1",
                    focus_start_date_range=str(start), focus_end_date_range=str(end))
    form = {"direction": "none", "ajaxtype": "jumper", "nonce": nonce,
            "start": str(start), "end": str(end)}
    form.update({f"shortcode[{key}]": value for key, value in selected.items()})
    return urlencode(form).encode("utf-8")


class _RejectRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


def _verified_response(body, year, month):
    try:
        data = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CalendarError("EventON response is not complete JSON") from error
    if not isinstance(data, dict) or data.get("status") != "GOOD":
        raise CalendarError("EventON response status is not GOOD")
    if not isinstance(data.get("json"), list) or not isinstance(data.get("html"), str):
        raise CalendarError("EventON response lacks full event data")
    title = data.get("cal_month_title")
    if not isinstance(title, str):
        raise CalendarError("EventON response lacks selected month title")
    title_text = re.sub(r"<[^>]*>", " ", title).lower()
    if MONTHS_IT[month - 1] not in title_text or str(year) not in title_text:
        raise CalendarError("EventON response month title differs from selection")
    returned = data.get("SC")
    if not isinstance(returned, dict) or str(returned.get("fixed_year")) != str(year) or str(returned.get("fixed_month")) != str(month):
        raise CalendarError("EventON response shortcode differs from selection")
    return data


def capture_months(source_page, endpoint, year, months, output_dir, client=None):
    """POST selected months, validate them, then retain exact bytes and receipts.

    `source_page` is public HTML bytes. `client` supplies the shared host lease.
    Existing output files are refused to avoid replacing earlier evidence.
    """
    parts = urlsplit(endpoint)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        raise CalendarError("invalid EventON endpoint")
    if parts.query != "evo-ajax=eventon_get_events" or parts.fragment:
        raise CalendarError("endpoint must be the EventON event request")
    shortcode, nonce = _page_config(source_page)
    months = list(months)
    if len(set(months)) != len(months):
        raise CalendarError("duplicate selected month")
    for month in months:
        _month_range(year, month)
    client = client or AcquisitionClient()
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    if stat.S_IMODE(output_dir.stat().st_mode) & 0o077:
        raise CalendarError("capture directory must be private")
    opener = build_opener(_RejectRedirect)
    receipts = []
    for month in months:
        name = f"{year:04d}-{month:02d}"
        original_path = output_dir / f"{name}.json"
        receipt_path = output_dir / f"{name}.receipt.json"
        if original_path.exists() or receipt_path.exists():
            raise CalendarError(f"capture already exists for {name}")
        request = Request(endpoint, data=_form(shortcode, nonce, year, month), method="POST",
                          headers={"Content-Type": "application/x-www-form-urlencoded"})
        with client.lease(endpoint):
            try:
                response = opener.open(request, timeout=client.policy_for(parts.hostname.lower()).timeout)
            except HTTPError as error:
                raise CalendarError(f"EventON HTTP {error.code} for {name}") from None
            with response:
                status = response.status
                final_url = response.geturl()
                content_type = response.headers.get("Content-Type", "")
                maximum = client.policy_for(parts.hostname.lower()).max_bytes
                body = response.read(maximum + 1)
        if status != 200 or final_url != endpoint:
            raise CalendarError(f"EventON unexpected response for {name}")
        if len(body) > maximum:
            raise CalendarError(f"EventON response exceeds byte limit for {name}")
        length_header = response.headers.get("Content-Length")
        if length_header is not None and int(length_header) != len(body):
            raise CalendarError(f"EventON incomplete response for {name}")
        media_type = content_type.split(";", 1)[0].strip().lower()
        if media_type != "application/json" and not media_type.endswith("+json"):
            raise CalendarError(f"EventON unexpected content type for {name}")
        data = _verified_response(body, year, month)
        start, end = _month_range(year, month)
        receipt = {"schema": SCHEMA, "captured_at": datetime.now(timezone.utc).isoformat(),
                   "source_page_sha256": sha256(source_page).hexdigest(),
                   "requested_url": endpoint, "final_url": final_url,
                   "redirects": [], "http_status": status, "content_type": content_type,
                   "length": len(body), "sha256": sha256(body).hexdigest(),
                   "selected_year": year, "selected_month": month,
                   "selection": {"direction": "none", "ajaxtype": "jumper",
                                 "event_type": str(shortcode["event_type"]),
                                 "fixed_day": 1, "start": start, "end": end,
                                 "focus_start_date_range": start,
                                 "focus_end_date_range": end},
                   "event_count": len(data["json"])}
        _write_private(original_path, body)
        _write_private(receipt_path, (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode())
        receipts.append(receipt)
    return receipts


def _write_private(path, body):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(body)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_page", type=Path, help="fresh public FIPSAS apnea HTML")
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--year", type=int, default=2025)
    parser.add_argument("--months", type=int, nargs="+", default=list(range(1, 13)))
    parser.add_argument("--endpoint", default="https://fipsas.it/?evo-ajax=eventon_get_events")
    parser.add_argument("--lease-path", type=Path)
    args = parser.parse_args(argv)
    client = AcquisitionClient(lease_path=args.lease_path)
    receipts = capture_months(args.source_page.read_bytes(), args.endpoint, args.year,
                              args.months, args.output_dir, client)
    print(json.dumps({"schema": SCHEMA, "months": [r["selected_month"] for r in receipts],
                      "receipts": [str(args.output_dir / f'{args.year:04d}-{r["selected_month"]:02d}.receipt.json') for r in receipts]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
