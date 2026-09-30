import http.server
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from bridge.notifications import Notifications, publish, settings, save_settings, write_json
from bridge.service import LiveSession

ROOT = Path(__file__).resolve().parents[1]


class NotificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT/'.tmp')
        self.directory = Path(self.temp.name)
        self.thread = str(uuid.uuid4())
        self.session = LiveSession(self.thread)
        self.session.connected = True
        self.session.state = {'title': 'Private project', 'requests': [
            {'id': 'approval', 'method': 'item/commandExecution/requestApproval', 'params': {'command': 'secret command'}}]}
        session = self.session
        class Source:
            def for_host(self, host):
                if host not in ('local', 'remote'): raise KeyError(host)
                return self
            def session(self, thread, **kwargs):
                return session
        self.source = Source()
        self.manager = Notifications(self.source, self.directory, lambda: ['http://10.0.0.1:8787', 'https://example.com'])
        save_settings(self.directory, {'enabled': True, 'topic': 'test', 'token': 'secret-token'})
        self.manager.watch(self.thread, 'remote', True)

    def tearDown(self):
        self.manager.close()
        self.temp.cleanup()

    def test_watch_runs_without_viewers_and_deduplicates_after_restart(self):
        with patch('bridge.notifications.publish') as send:
            self.manager.scan(); self.manager.scan()
            self.assertEqual(send.call_count, 1)
            self.assertTrue(self.session.watched)
            self.assertEqual(self.session.viewers, 0)
            config, title, body, click = send.call_args.args
            self.assertNotIn('secret command', body)
            self.assertNotIn('Private project', body)
            self.assertEqual(click, 'https://example.com/#'+self.thread+'~remote')
            other = Notifications(self.source, self.directory)
            other.scan()
            self.assertEqual(send.call_count, 1)
            other.close()

    def test_new_request_notifies_and_completed_request_does_not_retry(self):
        with patch('bridge.notifications.publish', side_effect=OSError('offline')) as send:
            self.manager.scan()
            self.assertEqual(send.call_count, 1)
        for record in self.manager.ledger.values(): record['next'] = 0
        self.session.state['requests'] = []
        with patch('bridge.notifications.publish') as send:
            self.manager.scan();self.assertFalse(send.called)
            self.session.state['requests'] = [{'id': 'another', 'method': 'item/tool/requestUserInput', 'params': {'questions': []}}]
            self.manager.scan();self.assertEqual(send.call_count, 1)

    def test_disabled_or_offline_history_never_pushes(self):
        with patch('bridge.notifications.publish') as send:
            self.session.connected = False
            self.manager.scan();self.assertFalse(send.called)
            save_settings(self.directory, {'enabled': False})
            self.manager.scan();self.assertFalse(self.session.watched)
            self.assertFalse(send.called)

    def test_coalesces_pending_requests_and_unwatch_releases(self):
        self.session.state['requests'].append({'id': 2, 'method': 'item/permissions/requestApproval', 'params': {}})
        with patch('bridge.notifications.publish') as send:
            self.manager.scan();self.assertEqual(send.call_count, 1)
            self.assertIn('2 项', send.call_args.args[2])
            self.manager.watch(self.thread, 'remote', False)
            self.manager.scan();self.assertFalse(self.session.watched)

    def test_async_question_is_detected(self):
        self.session.state = {'turns': [{'turnId': 't', 'status': 'inProgress', 'items': [
            {'type': 'agentMessage', 'id': 'q', 'questions': [{'title': 'Choose', 'options': None}]}]}]}
        with patch('bridge.notifications.publish') as send:
            self.manager.scan();self.assertEqual(send.call_count, 1)

    def test_config_token_preservation_and_destination_change(self):
        save_settings(self.directory, {'token': ''})
        self.assertEqual(settings(self.directory)['token'], 'secret-token')
        save_settings(self.directory, {'server': 'https://new.example.com', 'token': ''})
        self.assertEqual(settings(self.directory)['token'], '')
        for change in [{'server': 'https://user:pass@example.com'}, {'topic': '../bad'}, {'token': 'x\nInjected'}, {'enabled': 'yes'}]:
            with self.assertRaises(ValueError): save_settings(self.directory, change)
        with self.assertRaises(KeyError): self.manager.watch(self.thread, 'unknown', True)

    def test_real_http_payload_and_authorization(self):
        captured = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                captured.append((json.loads(self.rfile.read(int(self.headers['Content-Length']))), self.headers.get('Authorization')))
                self.send_response(200);self.end_headers();self.wfile.write(b'{}')
            def log_message(self, *args): pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            publish({'server': 'http://127.0.0.1:'+str(server.server_port), 'topic': 'topic', 'token': 'test-token'}, '待确认', 'Open chat', 'https://example.com/#thread')
            self.assertEqual(captured[0][1], 'Bearer test-token')
            self.assertEqual(captured[0][0]['click'], 'https://example.com/#thread')
            self.assertNotIn('token', captured[0][0])
        finally:
            server.shutdown();server.server_close()
