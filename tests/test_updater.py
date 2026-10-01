import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from bridge import updater


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1] / '.tmp'
        root.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def unpack(self, entries):
        archive = self.root / 'update.zip'
        with zipfile.ZipFile(archive, 'w') as target:
            for name, value, mode in entries:
                info = zipfile.ZipInfo('entry')
                # Preserve malformed wire names; ZipInfo(name) normalizes them
                # on Windows before the test archive is even written.
                info.filename = name
                info.orig_filename = name
                info.external_attr = mode << 16
                target.writestr(info, value)
        dest = self.root / 'unpacked'
        dest.mkdir(exist_ok=True)
        updater.extract(archive, dest)
        return dest

    def test_reject_path_traversal_before_writing(self):
        for name in ('../escape', '/absolute', 'x/../../escape', 'x\\escape', 'C:/escape', 'x/./y', 'x\0escape'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.unpack([(name, 'bad', stat.S_IFREG | 0o644)])
        self.assertFalse((self.root / 'escape').exists())

    def test_reject_external_links_and_writes_through_links(self):
        if updater.sys.platform == 'win32':
            self.skipTest('Windows rejects all archive symlinks')
        for entries in ([('link', '../outside', stat.S_IFLNK | 0o777)],
                        [('folder/link', '/outside', stat.S_IFLNK | 0o777)],
                        [('link', 'folder', stat.S_IFLNK | 0o777), ('link/file', 'bad', stat.S_IFREG | 0o644)]):
            with self.assertRaises(ValueError):
                self.unpack(entries)

    def test_reject_case_collisions_and_special_files(self):
        with self.assertRaises(ValueError):
            self.unpack([('file', 'a', stat.S_IFREG | 0o644), ('FILE', 'b', stat.S_IFREG | 0o644)])
        with self.assertRaises(ValueError):
            self.unpack([('socket', '', stat.S_IFSOCK | 0o644)])

    @unittest.skipIf(updater.sys.platform == 'win32', 'Unix executable permissions and symlinks')
    def test_preserve_executable_and_internal_framework_symlink(self):
        dest = self.unpack([('Versions/A/Executable', 'program', stat.S_IFREG | 0o755),
                            ('Versions/Current', 'A', stat.S_IFLNK | 0o777),
                            ('Executable', 'Versions/Current/Executable', stat.S_IFLNK | 0o777)])
        self.assertEqual((dest / 'Executable').read_text(), 'program')
        self.assertTrue((dest / 'Executable').stat().st_mode & stat.S_IXUSR)


class TransactionTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1] / '.tmp'
        root.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.target, self.staged, self.backup = (self.root / name for name in ('app', 'staged', 'previous'))
        for directory, version in ((self.target, 'old'), (self.staged, 'new')):
            directory.mkdir()
            (directory / 'version').write_text(version)
        self.data = self.root / 'data'; self.data.mkdir()
        self.configs = ['config.json', 'desktop.json', 'notifications.json', 'notification-watches.json']
        for name in self.configs:
            (self.data / name).write_text('preserve exactly: ' + name)
        self.state = {'running': True, 'portOccupied': False, 'instanceId': 'old'}
        self.calls = []
        outer = self
        class Desktop:
            def status(self):
                return dict(outer.state)
            def stop(self):
                outer.calls.append('stop')
                outer.state.update(running=False, instanceId=None)
        self.desktop = Desktop()
        self.plan = {'target': str(self.target), 'staged': str(self.staged), 'backup': str(self.backup),
                     'dataDir': str(self.data), 'version': '0.2.0-beta.6', 'parentPid': 999999,
                     'token': 'test', 'runtime': dict(self.state)}
        self.plan_file = self.root / 'plan.json'
        self.plan_file.write_text(json.dumps(self.plan))
        self.addCleanup(self.check_configs)

    def check_configs(self):
        for name in self.configs:
            self.assertEqual((self.data / name).read_text(), 'preserve exactly: ' + name)

    def start(self, target, data_dir, desktop):
        self.calls.append('start-' + (target / 'version').read_text())
        self.state.update(running=True, instanceId='new')

    def launch(self, target, transaction, plan, acknowledge=True):
        self.calls.append('launch-' + (target / 'version').read_text())
        class Child:
            def poll(self): return None
            def terminate(self): pass
            def wait(self, timeout): pass
        return Child()

    def run_update(self, health=lambda *args: None, wait=lambda pid: None):
        with patch.object(updater, 'registry_version'):
            return updater.apply(self.plan_file, desktop=self.desktop, wait=wait,
                                 launch=self.launch, health=health, start=self.start)

    def test_success_stops_and_restarts_gateway_and_keeps_backup(self):
        result = self.run_update()
        self.assertEqual(result['state'], 'updated')
        self.assertEqual(self.calls, ['stop', 'launch-new', 'start-new'])
        self.assertEqual((self.target / 'version').read_text(), 'new')
        self.assertEqual((self.backup / 'version').read_text(), 'old')

    def test_unhealthy_app_rolls_back_before_restoring_gateway(self):
        def fail(*args): raise RuntimeError('health failed')
        result = self.run_update(health=fail)
        self.assertTrue(result['recovered'])
        self.assertEqual(result['state'], 'failed')
        self.assertEqual((self.target / 'version').read_text(), 'old')
        self.assertEqual(self.calls, ['stop', 'launch-new', 'start-old', 'launch-old'])

    def test_parent_timeout_never_stops_or_replaces_running_app(self):
        def fail(pid): raise TimeoutError('still running')
        self.assertEqual(self.run_update(wait=fail)['state'], 'failed')
        self.assertEqual(self.calls, [])
        self.assertEqual((self.target / 'version').read_text(), 'old')

    def test_changed_gateway_instance_is_not_stopped(self):
        self.state['instanceId'] = 'someone-else'
        self.assertEqual(self.run_update()['state'], 'failed')
        self.assertNotIn('stop', self.calls)
        self.assertEqual((self.target / 'version').read_text(), 'old')

    def test_stopped_gateway_remains_stopped(self):
        self.state.update(running=False, instanceId=None)
        self.plan['runtime'] = dict(self.state)
        self.plan_file.write_text(json.dumps(self.plan))
        self.run_update()
        self.assertEqual(self.calls, ['launch-new'])
        self.assertFalse(self.state['running'])

    def test_failed_gateway_start_restores_original_app_and_gateway(self):
        normal = self.start
        def start(target, data_dir, desktop):
            if (target / 'version').read_text() == 'new':
                raise RuntimeError('new gateway failed')
            normal(target, data_dir, desktop)
        self.start = start
        result = self.run_update()
        self.assertTrue(result['recovered'])
        self.assertEqual((self.target / 'version').read_text(), 'old')
        self.assertIn('start-old', self.calls)


if __name__ == '__main__':
    unittest.main()
