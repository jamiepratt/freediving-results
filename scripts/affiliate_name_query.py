"""Verified, read-only private affiliate name evidence input for the owner viewer."""

import hashlib
import json
from pathlib import Path
import re


HEX = re.compile(r'[0-9a-f]{64}\Z')
MAX_INPUT = 8 * 1024 * 1024
MAX_SOURCE = 2 * 1024 * 1024


def _read(path, limit):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise ValueError('private evidence file unavailable or too large')
    data = path.read_bytes()
    if len(data) > limit:
        raise ValueError('private evidence file too large')
    return data


def _digest(data):
    return hashlib.sha256(data).hexdigest()


class AffiliateNameQuery:
    def __init__(self, input_path, expected_sha256, snapshot_dir, snapshot):
        if not isinstance(expected_sha256, str) or not HEX.fullmatch(expected_sha256):
            raise ValueError('invalid affiliate input digest')
        self.input_path = Path(input_path)
        self.snapshot_manifest_path = Path(snapshot_dir) / 'manifest.json'
        raw = _read(self.input_path, MAX_INPUT)
        if _digest(raw) != expected_sha256:
            raise ValueError('affiliate input digest mismatch')
        self.sha256 = expected_sha256
        data = json.loads(raw)
        if data.get('schema') != 'affiliate-name-input/v1':
            raise ValueError('unsupported affiliate input schema')
        pinned = data.get('snapshot') or {}
        manifest_raw = _read(self.snapshot_manifest_path, MAX_INPUT)
        if (pinned.get('manifest_sha256') != _digest(manifest_raw)
                or pinned.get('sqlite_sha256') != snapshot.manifest['snapshot_sha256']
                or pinned.get('cutoff') != snapshot.manifest.get('cutoff')):
            raise ValueError('affiliate input snapshot mismatch')
        if not isinstance(data.get('sources'), list) or len(data['sources']) > 10000:
            raise ValueError('invalid affiliate sources')
        self.sources = {}
        self.receipt_hashes = {}
        for item in data.get('sources', []):
            sha = item.get('source_sha256')
            if not isinstance(sha, str) or not HEX.fullmatch(sha) or sha in self.sources:
                raise ValueError('invalid affiliate source digest')
            self._verify_source(item)
            self.sources[sha] = item
            self.receipt_hashes[sha] = _digest(_read(item['receipt_path'], MAX_INPUT))
        assertions = data.get('assertions')
        if not isinstance(assertions, list) or len(assertions) > 10000:
            raise ValueError('invalid affiliate assertions')
        keys = set()
        for item in assertions:
            key = item.get('evidence_key')
            position = item.get('source_position') or {}
            if (not isinstance(key, str) or not key or key in keys
                    or item.get('source_sha256') not in self.sources
                    or position.get('format') != 'html'
                    or not isinstance(position.get('selector'), str) or not position['selector']
                    or not any(type(position.get(field)) is int and 1 <= position[field] <= 100000
                               for field in ('row', 'ordinal'))
                    or not isinstance(item.get('original_name'), str) or not item['original_name']
                    or not isinstance(item.get('candidate_observation_refs'), list)
                    or not isinstance(item.get('uncertainty'), list)):
                raise ValueError('invalid affiliate assertion')
            keys.add(key)
        if not isinstance(data.get('gaps'), list) or not isinstance(data.get('roster'), dict):
            raise ValueError('invalid affiliate coverage')
        self.data = data

    def _verify_source(self, item):
        raw = _read(item['path'], MAX_SOURCE)
        if _digest(raw) != item['source_sha256']:
            raise ValueError('affiliate source digest mismatch')
        receipt = json.loads(_read(item['receipt_path'], MAX_INPUT))
        if (receipt.get('sha256') != item['source_sha256']
                or any(receipt.get(field) != item.get(field)
                       for field in ('requested_url', 'final_url', 'retrieved_at'))):
            raise ValueError('affiliate source receipt mismatch')
        return raw

    def listing(self):
        if _digest(_read(self.input_path, MAX_INPUT)) != self.sha256:
            raise ValueError('affiliate input changed')
        if _digest(_read(self.snapshot_manifest_path, MAX_INPUT)) != self.data['snapshot']['manifest_sha256']:
            raise ValueError('pinned snapshot manifest changed')
        for sha, item in self.sources.items():
            if _digest(_read(item['receipt_path'], MAX_INPUT)) != self.receipt_hashes[sha]:
                raise ValueError('affiliate receipt changed')
            self._verify_source(item)
        return {**self.data, 'input_sha256': self.sha256,
                'identity_status': 'candidate_only', 'attempt_imported': False}

    def original(self, sha):
        item = self.sources.get(sha)
        if item is None:
            return None
        self.listing()
        return self._verify_source(item).decode('utf-8')
