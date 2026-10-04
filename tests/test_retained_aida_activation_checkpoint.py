import copy
import hashlib
import json
import shutil
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.retained_aida_activation_checkpoint import prepare_checkpoint
from tests.test_retained_aida_promotion import fixture, evidence, SHA


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class ActivationCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.out = self.root / 'private'
        self.out.mkdir(mode=0o700)
        self.snapshot = self.root / 'snapshot'
        self.snapshot.mkdir()
        self.handoff, self.rows, self.owner = fixture()
        self.preflight = {
            'schema': 'retained-aida-promotion-preflight/v1',
            'snapshot_sha256': SHA,
            'source_rows': self.handoff['canonical_readback']['source-rows'],
            'expected_production_revision': 0,
            'canonical_replay': [e['request'] for e in self.handoff['canonical_readback']['events']],
            'owner': {'binding_revision': 1, 'store_revision': 1,
                      'status': 'requires_reconciliation_binding',
                      'candidate_intents': [{'source_event_id': 'edge-2', 'status': 'pending'}]},
            'counts': {'source_rows': 3, 'canonical_events': 3,
                       'human_reversals': 1, 'owner_candidate_intents': 1},
            'authority': 'read_only_preflight_recheck_targets_before_apply',
        }
        self.envelope = {
            'snapshot_sha256': SHA, 'binding_revision': 1, 'store_revision': 1,
            'reconciliation_run_revision': 1,
            'counts': {'active_intents': 1, 'supported': 1, 'unresolved': 0},
            'proposals': [{'id': 'pending-1', 'status': 'pending',
                           'canonical_binding': {'source_event_id': 'edge-2'}}],
        }
        self.target = {'schema': 'retained-aida-target-state/v1',
                       'snapshot_sha256': SHA, 'revision': 0, 'events': [],
                       'source_rows': [], 'non_source_row_count': 0}
        self.db_path = self.root / 'owner.sqlite'
        db = sqlite3.connect(self.db_path)
        db.executescript('''CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
            INSERT INTO meta VALUES ('revision','1');
            CREATE TABLE bindings (revision INTEGER, snapshot_sha256 TEXT,
                observation_refs_json TEXT);
            CREATE TABLE proposals (id TEXT);
            CREATE TABLE events (revision INTEGER);
            CREATE TABLE operations (idempotency_key TEXT);''')
        db.execute('INSERT INTO bindings VALUES (?,?,?)',
                   (1, SHA, json.dumps(self.owner['observation_refs'])))
        db.commit()
        db.execute('PRAGMA journal_mode=WAL')
        db.close()
        self.db_path.chmod(0o600)
        self.paths = {}
        for name, value in (('preflight', self.preflight), ('envelope', self.envelope),
                            ('target', self.target)):
            path = self.root / f'{name}.json'
            path.write_text(json.dumps(value))
            self.paths[name] = path

    def prepare(self):
        with patch('scripts.retained_aida_activation_checkpoint.load_source_observations',
                   return_value={'snapshot_sha256': SHA, 'observations': self.rows, 'gaps': []}):
            return prepare_checkpoint(
                self.paths['preflight'], sha(self.paths['preflight']),
                self.paths['envelope'], sha(self.paths['envelope']),
                self.paths['target'], sha(self.paths['target']),
                self.db_path, self.snapshot, {}, self.out)

    def test_prepare_restores_owner_backup_and_is_idempotent(self):
        first = self.prepare()
        second = self.prepare()
        self.assertEqual(first, second)
        self.assertEqual(first['authority'], 'read_only_no_live_apply')
        self.assertEqual(first['next_checkpoint'], 'fresh_live_target_and_owner_readback_before_any_write')
        self.assertEqual(first['counts']['pending_proposals'], 1)
        backup = self.out / 'owner-backup.sqlite'
        restored = self.out / 'owner-restore-drill.sqlite'
        self.assertTrue(backup.is_file() and restored.is_file())
        self.assertEqual(sha(backup), sha(restored))
        self.assertFalse(list(self.out.glob('*.sqlite-*')))
        standalone = self.root / 'standalone'
        standalone.mkdir()
        isolated = standalone / 'owner.sqlite'
        shutil.copyfile(backup, isolated)
        with sqlite3.connect(isolated.resolve().as_uri() + '?mode=ro&immutable=1',
                             uri=True) as copied:
            self.assertEqual(copied.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
            self.assertEqual(copied.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0], '1')
        self.assertEqual((self.out / 'checkpoint.json').stat().st_mode & 0o077, 0)
        self.assertEqual((self.out / 'owner-backup.sqlite').stat().st_mode & 0o077, 0)

    def test_changed_target_owner_or_envelope_fails_closed(self):
        self.prepare()
        changed = copy.deepcopy(self.target)
        changed['revision'] = 1
        self.paths['target'].write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, 'target'):
            self.prepare()
        self.paths['target'].write_text(json.dumps(self.target))
        db = sqlite3.connect(self.db_path)
        db.execute("UPDATE meta SET value='2' WHERE key='revision'")
        db.commit()
        db.close()
        with self.assertRaisesRegex(ValueError, 'owner'):
            self.prepare()

    def test_human_event_and_unexpected_proposal_stop_before_backup(self):
        for table, insert in (('events', 'INSERT INTO events VALUES (1)'),
                              ('proposals', "INSERT INTO proposals VALUES ('unexpected')")):
            with self.subTest(table=table):
                db = sqlite3.connect(self.db_path)
                db.execute(insert)
                db.commit()
                db.close()
                with self.assertRaisesRegex(ValueError, 'owner'):
                    self.prepare()
                self.assertFalse((self.out / 'owner-backup.sqlite').exists())
                db = sqlite3.connect(self.db_path)
                db.execute(f'DELETE FROM {table}')
                db.commit()
                db.close()

    def test_changed_source_ref_or_corrupt_restore_blocks_checkpoint(self):
        self.rows[0]['source_observation_ref']['observation_version'] = 'e' * 64
        with self.assertRaisesRegex(ValueError, 'source'):
            self.prepare()
        self.rows = [evidence(record) for record in ('1' * 64, '2' * 64, '3' * 64)]
        self.prepare()
        (self.out / 'owner-restore-drill.sqlite').write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'restore'):
            self.prepare()

    def test_missing_or_duplicate_pending_source_lineage_is_rejected(self):
        changed = copy.deepcopy(self.envelope)
        changed['proposals'][0].pop('canonical_binding')
        self.paths['envelope'].write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, 'envelope'):
            self.prepare()
        changed = copy.deepcopy(self.envelope)
        changed['proposals'].append({'id': 'pending-2', 'status': 'pending',
                                     'canonical_binding': {'source_event_id': 'edge-2'}})
        changed['counts']['supported'] = 2
        changed['counts']['unresolved'] = -1
        self.paths['envelope'].write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, 'envelope'):
            self.prepare()

    def test_partial_checkpoint_resumes_without_replacing_owner_backup(self):
        self.prepare()
        backup_sha = sha(self.out / 'owner-backup.sqlite')
        for name in ('owner-restore-drill.sqlite', 'phase-restore-drill.json',
                     'checkpoint.json'):
            (self.out / name).unlink()
        self.prepare()
        self.assertEqual(sha(self.out / 'owner-backup.sqlite'), backup_sha)
        self.assertTrue((self.out / 'checkpoint.json').is_file())


if __name__ == '__main__':
    unittest.main()
