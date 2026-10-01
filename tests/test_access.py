import copy
import io
import json
import os
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from bridge import access
from bridge.desktop import Desktop
from bridge.notifications import Notifications, write_json
from bridge.ssh_tunnel import SSHTunnel, command

ROOT = Path(__file__).resolve().parents[1]


class AccessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT/'.tmp')
        self.directory = Path(self.temp.name)
        self.desktop = Desktop(self.directory)
        self.desktop.status = lambda: {'running': False, 'pid': None}
        self.value = self.desktop.snapshot()
        self.value['preferences'].update(codexHome=str(self.directory))

    def tearDown(self):
        self.temp.cleanup()

    def server(self):
        value = copy.deepcopy(self.value)
        value['preferences'].update(accessMode='server', publicUrl='https://codex.example.com/', sshTarget='my-server')
        return value

    def test_legacy_quick_settings_roundtrip_keeps_lan_and_port(self):
        write_json(self.directory/'desktop.json', {'tunnel': True, 'port': 8787, 'lan': True})
        self.assertEqual(self.desktop.preferences()['accessMode'], 'quick')
        value = self.desktop.snapshot()
        value['preferences']['codexHome'] = str(self.directory)
        self.desktop.save(value)
        self.assertTrue(self.desktop.preferences()['tunnel'])
        self.assertEqual(self.desktop.preferences()['port'], 8787)

    def test_fixed_url_allowlist_export_and_mode_switch(self):
        value = self.server()
        value['origins'] = ['https://another.example.com']
        self.desktop.save(value)
        state = self.desktop.snapshot()
        self.assertEqual(state['urls'][0], 'https://codex.example.com/')
        self.assertEqual(self.desktop.config()['publicUrl'], 'https://codex.example.com')
        self.assertIn('https://codex.example.com', state['origins'])
        self.assertIn('--ssh-target', self.desktop.argv())
        self.assertNotIn('--tunnel', self.desktop.argv())
        path = self.desktop.export_deployment()['path']
        with zipfile.ZipFile(path) as archive:
            self.assertEqual(set(archive.namelist()), {'compose.yaml', 'Caddyfile', '部署说明.md'})
            self.assertIn('127.0.0.1:18787', archive.read('Caddyfile').decode())
            self.assertNotIn('首次登录.txt', archive.namelist())
        state['preferences']['accessMode'] = 'lan'
        self.desktop.save(state)
        self.assertEqual(self.desktop.config()['origins'], ['https://another.example.com'])
        self.assertEqual(self.desktop.config()['publicUrl'], '')
        self.assertNotIn('--ssh-target', self.desktop.argv())
        self.assertEqual(self.desktop.preferences()['port'], 8787)

    def test_invalid_input_never_partially_saves(self):
        before = self.desktop.config()
        for changes in [dict(publicUrl='https://a.example/path'), dict(publicUrl='https://x;evil'),
                        dict(publicUrl='https://user:password@a.example'), dict(publicUrl='http://a.example'),
                        dict(publicUrl='https://example.com:8443'), dict(sshTarget='-oProxyCommand=evil'),
                        dict(sshTarget='a; echo bad'), dict(sshRemotePort=True), dict(sshRemotePort=22)]:
            value = self.server()
            value['preferences'].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.desktop.save(value)
            self.assertEqual(self.desktop.config(), before)

    def test_default_https_port_matches_browser_origin_serialization(self):
        value = self.server()
        value['preferences']['publicUrl'] = 'https://CODEX.example.com:443/'
        self.desktop.save(value)
        self.assertEqual(self.desktop.config()['origins'], ['https://codex.example.com'])

    def test_nas_requires_reachable_computer_address_and_generates_literal_config(self):
        value = self.value
        value['preferences'].update(accessMode='nas', publicUrl='https://codex.example.com:8443',
                                    proxyUpstream='http://192.168.2.30:8787')
        self.desktop.save(value)
        files = self.desktop.deployment()['files']
        self.assertIn('proxy_pass http://192.168.2.30:8787;', files['nginx.conf'])
        self.assertIn('Host $http_host', files['nginx.conf'])
        self.assertIn('127.0.0.1', files['compose.yaml'])
        for changes in [dict(lan=False), dict(proxyUpstream='http://127.0.0.1:8787'),
                        dict(proxyUpstream='http://192.168.2.30:80'), dict(proxyUpstream='http://x/${evil}:8787')]:
            invalid = copy.deepcopy(value)
            invalid['preferences'].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.desktop.save(invalid)

    def test_running_configuration_cannot_change_external_access(self):
        self.desktop.save(self.value)
        self.desktop.status = lambda: {'running': True, 'pid': os.getpid()}
        with self.assertRaisesRegex(ValueError, '先停止'):
            self.desktop.save(self.server())
        invalid = copy.deepcopy(self.value)
        invalid['origins'] = ['https://new.example.com']
        with self.assertRaisesRegex(ValueError, '先停止'):
            self.desktop.save(invalid)

    def test_probe_checks_current_instance_and_does_not_send_credentials(self):
        preferences = self.server()['preferences']
        for remote, success in [({'instanceId': 'same'}, True), ({'instanceId': 'another'}, False), ({}, False)]:
            with patch('bridge.access.read_auth', side_effect=[{'instanceId': 'same'}, remote]) as read:
                if success:
                    self.assertIn('已连到当前网关', access.check_entry(preferences)['message'])
                else:
                    with self.assertRaisesRegex(ValueError, '当前网关'):
                        access.check_entry(preferences)
                self.assertEqual(read.call_args_list[0].args, ('http://127.0.0.1:8787',))
        self.desktop.save(self.server())
        with self.assertRaisesRegex(ValueError, '先启动'):
            self.desktop.check_entry()

    def test_fixed_notification_url_precedence_keeps_explicit_override(self):
        notifications = Notifications(None, self.directory, lambda: ['https://a.trycloudflare.com'], lambda: 'https://fixed.example.com')
        self.assertTrue(notifications.click_url({}, 'id', 'local').startswith('https://fixed.example.com/#'))
        self.assertTrue(notifications.click_url({'clickBase': 'https://override.example.com'}, 'id', 'local').startswith('https://override.example.com/#'))

    def test_old_tunnel_url_and_stale_ssh_status_are_not_shown(self):
        self.desktop.save(self.server())
        (self.directory/'外网地址.txt').write_text('https://stale.trycloudflare.com')
        write_json(self.directory/'ssh-status.json', {'pid': 101, 'message': 'old connection'})
        self.desktop.status = lambda: {'running': True, 'pid': 102}
        state = self.desktop.snapshot()
        self.assertFalse(any('trycloudflare' in u for u in state['urls']))
        self.assertEqual(state['externalStatus'], {})


class SSHTests(unittest.TestCase):
    def test_command_uses_private_forward_and_existing_identity(self):
        with patch('bridge.ssh_tunnel.shutil.which', return_value='/usr/bin/ssh'):
            args = command('my-server', 18787, 8787)
        self.assertEqual(args[-2:], ['127.0.0.1:18787:127.0.0.1:8787', 'my-server'])
        for value in ['StrictHostKeyChecking=yes', 'BatchMode=yes', 'ExitOnForwardFailure=yes',
                      'ControlPath=none', 'ForwardAgent=no', 'UpdateHostKeys=no']:
            self.assertIn(value, args)

    def test_success_close_and_failed_connection_retry(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.tmp') as folder, patch('bridge.ssh_tunnel.shutil.which', return_value='ssh'):
            tunnel = SSHTunnel('test', 18787, 8787, folder)
            seen = []
            ready = threading.Event()
            real_status = tunnel.status
            def status(state, message):
                seen.append(state)
                real_status(state, message)
                if state == 'retrying':
                    ready.set()
            tunnel.status = status
            class Process:
                stderr = io.StringIO('debug1: remote forward success for: listen 127.0.0.1:18787, connect 127.0.0.1:8787\n')
                def wait(self, **kwargs): return 255
                def poll(self): return 255
            with patch('bridge.ssh_tunnel.subprocess.Popen', return_value=Process()) as spawn:
                tunnel.start()
                self.assertTrue(ready.wait(2))
                tunnel.close()
                self.assertFalse(tunnel.thread.is_alive())
                self.assertEqual(spawn.call_count, 1)
            self.assertEqual(seen, ['connecting', 'connected', 'retrying', 'stopped'])
            self.assertEqual(json.loads((Path(folder)/'ssh-status.json').read_text(encoding='utf-8'))['state'], 'stopped')
