"""Public, read-only setup contract for the remote ingestion worker."""

import subprocess
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "setup-ingestion-worker.sh"


class WorkerSetupTest(unittest.TestCase):
    def test_describe_names_isolated_resources_without_mutation(self):
        result = subprocess.run(["bash", str(SCRIPT), "--describe"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout.splitlines(),
            [
                "account=freediving-ingest",
                "database=freediving_ingest",
                "owner_role=freediving_ingest_owner",
                "app_role=freediving_ingest_app",
                "state=/var/lib/freediving-ingest",
                "archive=/srv/freediving-ingest/archive",
                "bundle=/srv/freediving-ingest/bundles",
                "lease=/var/lib/freediving-ingest/acquisition.sqlite3",
            ],
        )


if __name__ == "__main__":
    unittest.main()
