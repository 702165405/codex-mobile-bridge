import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from bridge.desktop import Desktop
from bridge.notifications import settings

ROOT = Path(__file__).resolve().parents[1]


class DesktopTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT/'.tmp')
        self.directory = Path(self.temp.name)
        self.desktop = Desktop(self.directory)
        self.desktop.status = lambda: {'running': False, 'portOccupied': False, 'supportsNotifications': False}

    def tearDown(self):
        self.temp.cleanup()

    def value(self):
        snapshot = self.desktop.snapshot()
        snapshot['preferences']['codexHome'] = str(self.directory)
        return snapshot

    def test_defaults_preserve_8787_and_private_credentials(self):
        snapshot = self.value()
        self.assertEqual(snapshot['preferences']['port'], 8787)
        self.assertEqual(snapshot['auth']['mode'], 'password')
        self.assertFalse(snapshot['notifications']['enabled'])
        self.assertNotIn('hash', snapshot['auth'])
        self.assertTrue((self.directory/'首次登录.txt').is_file())

    def test_save_roundtrip_and_secrets_are_not_returned(self):
        value = self.value()
        old_hash = self.desktop.config()['auth']['hash']
        value['notifications'].update(topic='private', token='private-token', enabled=True)
        self.desktop.save(value)
        snapshot = self.desktop.snapshot()
        self.assertEqual(snapshot['notifications']['token'], '')
        self.assertTrue(snapshot['notifications']['hasToken'])
        self.assertEqual(settings(self.directory)['token'], 'private-token')
        self.assertEqual(self.desktop.config()['auth']['hash'], old_hash)
        value['auth']['password'] = 'a new password for test'
        self.desktop.save(value)
        self.assertFalse((self.directory/'首次登录.txt').exists())
        self.assertNotEqual(self.desktop.config()['auth']['hash'], old_hash)

    def test_running_network_settings_cannot_disconnect_phone(self):
        value = self.value()
        self.desktop.save(value)
        self.desktop.status = lambda: {'running': True, 'portOccupied': False, 'supportsNotifications': True}
        value['preferences']['port'] = 9999
        with self.assertRaisesRegex(ValueError, '先停止'): self.desktop.save(value)
        self.assertEqual(self.desktop.preferences()['port'], 8787)

    def test_validation_does_not_partially_change_gateway_config(self):
        value = self.value();before = self.desktop.config()
        for modify in [lambda v:v['auth'].update(password='short'), lambda v:v.update(origins=['http://example.com']), lambda v:v['notifications'].update(server='file:///secret')]:
            invalid = copy.deepcopy(value);modify(invalid)
            with self.assertRaises(ValueError): self.desktop.save(invalid)
            self.assertEqual(self.desktop.config(), before)

    def test_arguments_are_preserved_as_separate_values(self):
        value = self.value();value['preferences'].update(ipcPath='pipe with spaces', codexBin='/path with spaces/codex', tunnel=False)
        self.desktop.save(value)
        argv = self.desktop.argv()
        self.assertEqual(argv[argv.index('--port')+1], '8787')
        self.assertEqual(argv[argv.index('--codex-bin')+1], '/path with spaces/codex')
        self.assertNotIn('--tunnel', argv)
        self.assertIn('--lan', argv)

    def test_running_gateway_is_adopted_without_duplicate_spawn(self):
        self.desktop.status = lambda: {'running': True}
        with patch('subprocess.Popen') as spawn:
            self.assertFalse(self.desktop.start()['started'])
            self.assertFalse(spawn.called)
