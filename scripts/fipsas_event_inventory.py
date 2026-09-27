"""Inventory event cards and publisher links in captured FIPSAS EventON months."""

import argparse
from datetime import datetime
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from fipsas_calendar import CalendarError, _verified_response


def _official_url(value):
    parts = urlsplit(value)
    if (parts.scheme != "https" or parts.hostname not in {"fipsas.it", "www.fipsas.it"}
            or parts.username or parts.password or parts.fragment):
        raise CalendarError(f"nonofficial FIPSAS URL: {value}")
    return value


class _CardLinks(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.active = None
        self.cards = {}
        self.anchor = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "div":
            self.depth += 1
            if "data-event_id" in attrs:
                event_id = int(attrs["data-event_id"])
                if self.active is not None or event_id in self.cards:
                    raise CalendarError("ambiguous EventON card HTML")
                self.active = (event_id, self.depth)
                self.cards[event_id] = {"event_url": None, "result_urls": [], "rules_urls": []}
        if tag == "a" and self.active is not None:
            self.anchor = {"href": attrs.get("href"), "itemprop": attrs.get("itemprop"), "text": ""}

    def handle_data(self, data):
        if self.anchor is not None:
            self.anchor["text"] += data

    def handle_endtag(self, tag):
        if tag == "a" and self.anchor is not None:
            self._record_anchor()
            self.anchor = None
        if tag == "div":
            if self.active is not None and self.depth == self.active[1]:
                self.active = None
            self.depth -= 1

    def _record_anchor(self):
        href = self.anchor["href"]
        if not href:
            return
        card = self.cards[self.active[0]]
        if self.anchor["itemprop"] == "url":
            url = _official_url(href)
            if not urlsplit(url).path.startswith("/events/"):
                raise CalendarError("EventON event URL is not an event page")
            if card["event_url"] is not None and card["event_url"] != url:
                raise CalendarError("multiple event URLs in one card")
            card["event_url"] = url
            return
        label = self.anchor["text"].strip().casefold()
        path = urlsplit(href).path.casefold()
        if not path.endswith(".pdf"):
            return
        if "regolament" in label or "regolament" in path:
            bucket = "rules_urls"
        elif any(word in label or word in path for word in ("classific", "risultat")):
            bucket = "result_urls"
        else:
            return
        url = _official_url(href)
        if url not in card[bucket]:
            card[bucket].append(url)


def inventory_month(source_bytes, year, month):
    """Return one record per JSON card and distinct official PDF result URLs."""
    data = _verified_response(source_bytes, year, month)
    parser = _CardLinks()
    parser.feed(data["html"])
    parser.close()
    cards = []
    result_urls = []
    for source in data["json"]:
        if not isinstance(source, dict):
            raise CalendarError("EventON card is not an object")
        event_id = source.get("event_id")
        if not isinstance(event_id, int) or event_id != source.get("ID"):
            raise CalendarError("EventON card ID is missing or inconsistent")
        links = parser.cards.get(event_id)
        if links is None or links["event_url"] is None:
            raise CalendarError(f"EventON card HTML or event URL missing for {event_id}")
        title = source.get("event_title")
        start = source.get("event_start_unix")
        if not isinstance(title, str) or not isinstance(start, int):
            raise CalendarError(f"EventON card title or date missing for {event_id}")
        date = datetime.fromtimestamp(start, ZoneInfo("Europe/Rome")).date().isoformat()
        card = {"event_id": event_id, "title": title, "date": date,
                "discipline": title.split(":", 1)[0].strip(), **links}
        cards.append(card)
        for url in links["result_urls"]:
            if url not in result_urls:
                result_urls.append(url)
    return {"year": year, "month": month, "cards": cards, "result_urls": result_urls}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("month_dir", type=Path, help="directory of YYYY-MM.json EventON responses")
    parser.add_argument("--year", required=True, type=int)
    parser.add_argument("--months", required=True, nargs="+", type=int)
    args = parser.parse_args(argv)
    months = [inventory_month((args.month_dir / f"{args.year:04d}-{month:02d}.json").read_bytes(),
                              args.year, month) for month in args.months]
    print(json.dumps({"months": months}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
