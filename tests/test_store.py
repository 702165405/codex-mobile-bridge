import hashlib
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from bridge.store import SessionStore

ROOT = Path(__file__).resolve().parents[1]


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / '.tmp')
        self.root = Path(self.temp.name)
        self.path = self.root / 'state_5.sqlite'
        db = sqlite3.connect(self.path)
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('CREATE TABLE threads(id TEXT, title TEXT, cwd TEXT, updated_at INT, archived INT, originator TEXT, source TEXT)')
        db.execute("INSERT INTO threads VALUES ('test', 'Saved chat', '/project', 1, 0, 'Codex Desktop', 'vscode')")
        db.commit()
        db.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchall()
        db.close()
        # All writers are closed and the fixture is checkpointed. Never do this
        # to a live Codex database.
        for suffix in ('-wal', '-shm'):
            Path(str(self.path) + suffix).unlink(missing_ok=True)
        self.store = SessionStore(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def test_checkpointed_wal_without_sidecars_is_readable_without_source_writes(self):
        before = hashlib.sha256(self.path.read_bytes()).hexdigest()
        self.assertEqual(self.store.list()[0]['title'], 'Saved chat')
        self.assertEqual(self.store.get('test')['cwd'], '/project')
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest(), before)
        self.assertEqual([p.name for p in self.root.iterdir()], ['state_5.sqlite'])

    def test_new_wal_commits_are_visible_after_snapshot_read(self):
        self.assertEqual(self.store.get('test')['title'], 'Saved chat')
        db = sqlite3.connect(self.path)
        try:
            db.execute("UPDATE threads SET title='Live update'")
            db.commit()
            self.assertEqual(self.store.get('test')['title'], 'Live update')
        finally:
            db.close()

    def test_snapshot_rejects_writer_starting_during_copy(self):
        import shutil
        from bridge.store import StoreUnavailable
        copy = shutil.copyfile
        writer = None
        def copy_then_write(source, target):
            nonlocal writer
            result = copy(source, target)
            writer = sqlite3.connect(self.path)
            writer.execute("UPDATE threads SET title='Concurrent update'")
            writer.commit()
            return result
        try:
            with patch('bridge.store.shutil.copyfile', side_effect=copy_then_write):
                with self.assertRaises(StoreUnavailable):
                    self.store.get('test')
            self.assertEqual(self.store.get('test')['title'], 'Concurrent update')
        finally:
            if writer:
                writer.close()

    def test_missing_database_reports_recoverable_error(self):
        from bridge.store import StoreUnavailable
        self.path.unlink()
        with self.assertRaises(StoreUnavailable):
            self.store.list()

    def test_corruption_does_not_use_snapshot_fallback(self):
        from bridge.store import StoreUnavailable
        self.path.write_bytes(b'not a database')
        with patch('bridge.store.shutil.copyfile') as copy:
            with self.assertRaises(StoreUnavailable):
                self.store.list()
            copy.assert_not_called()
