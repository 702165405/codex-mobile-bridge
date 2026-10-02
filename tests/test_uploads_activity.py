import base64
import http.client
import json
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import test_bridge as support
from bridge.uploads import Uploads, MAX_FILE
from bridge.ipc import IPCError
from bridge.remote import upload_file

THREAD = support.THREAD
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR7kAAAAASUVORK5CYII=')


class UploadActivityTests(unittest.TestCase):
    setUp = support.IntegrationTests.setUp
    tearDown = support.IntegrationTests.tearDown

    def upload(self, name='报告.txt', data=b'fixture attachment'):
        return self.bridge.upload(THREAD, str(uuid.uuid4()), name, data)

    def calls(self, method='thread-follower-start-turn'):
        return [r for r in self.fixture.requests if r['method'] == method]

    def test_multiple_files_and_image_only_use_original_owner_and_native_image_input(self):
        file, image = self.upload(), self.upload('photo.png', PNG)
        identifier = str(uuid.uuid4())
        self.assertEqual(self.fixture.requests, [])
        result = self.bridge.send(THREAD, '', identifier, attachments=[file['id'], image['id']])
        self.assertEqual(result['status'], 'accepted')
        call = self.calls()[0]
        self.assertEqual(call['targetClientId'], 'owner')
        turn = call['params']['turnStart']
        self.assertEqual(turn['request']['input'][1]['type'], 'localImage')
        self.assertEqual(Path(turn['request']['input'][1]['path']).read_bytes(), PNG)
        self.assertIn('报告.txt', turn['request']['input'][0]['text'])
        self.assertEqual(len(turn['context']['fileAttachments']), 2)
        self.assertTrue(turn['context']['inheritThreadSettings'])
        self.bridge.send(THREAD, '', identifier, attachments=[file['id'], image['id']])
        self.assertEqual(len(self.calls()), 1)
        with self.assertRaises(ValueError): self.bridge.send(THREAD, '', identifier, attachments=[file['id']])

    def test_queue_steer_and_goal_keep_attachments_and_objective(self):
        file = self.upload()
        self.fixture.state['threadRuntimeStatus']['type'] = 'active'
        session = self.bridge.session(THREAD)
        identifier = str(uuid.uuid4())
        self.bridge.send(THREAD, 'later', identifier, 'queue', attachments=[file['id']])
        self.assertEqual(self.bridge.view(THREAD)['submissions'][0]['attachments'], ['报告.txt'])
        self.bridge.send(THREAD, 'steer', str(uuid.uuid4()), 'steer', attachments=[file['id']])
        self.assertIn('报告.txt', self.calls('thread-follower-steer-turn')[0]['params']['input'][0]['text'])
        session.state['threadRuntimeStatus']['type'] = 'idle'
        key = THREAD+':'+identifier
        self.bridge._send_queued(session, key, self.bridge.submissions[key])
        self.assertEqual(self.calls()[0]['params']['turnStart']['context']['fileAttachments'][0]['label'], '报告.txt')
        self.bridge.send(THREAD, 'Exact objective', str(uuid.uuid4()), work_mode='goal', attachments=[file['id']])
        self.assertTrue(self.calls()[-1]['params']['turnStart']['request']['input'][0]['text'].endswith('Exact objective'))

    def test_upload_retry_and_host_chat_isolation(self):
        identifier = str(uuid.uuid4())
        first = self.bridge.upload(THREAD, identifier, 'file.txt', b'payload')
        self.assertEqual(first, self.bridge.upload(THREAD, identifier, 'file.txt', b'payload'))
        with self.assertRaises(ValueError): self.bridge.upload(THREAD, identifier, 'file.txt', b'changed')
        for store, thread in [(self.bridge.uploads, str(uuid.uuid4())), (Uploads(self.root/'another-host'), THREAD)]:
            with self.assertRaises(ValueError): store.resolve(thread, [identifier])
        for name in ('../private', 'x/y', 'x\\y', 'line\nbreak'):
            with self.assertRaises(ValueError): self.bridge.upload(THREAD, str(uuid.uuid4()), name, b'bad')
        with self.assertRaises(ValueError): self.upload(data=b'x'*(MAX_FILE+1))
        with self.assertRaises(ValueError): self.bridge.uploads.resolve(THREAD, [identifier]*2)

    def test_unknown_delivery_retains_same_file_and_does_not_resubmit(self):
        file = self.upload();identifier = str(uuid.uuid4())
        with patch.object(self.bridge, '_call', side_effect=IPCError('uncertain')) as call:
            with self.assertRaises(IPCError): self.bridge.send(THREAD, 'use file', identifier, attachments=[file['id']])
            self.assertEqual(self.bridge.send(THREAD, 'use file', identifier, attachments=[file['id']])['status'], 'unknown')
            self.assertEqual(call.call_count, 1)

    def test_remote_upload_is_idempotent_and_paths_are_not_local(self):
        received=[]
        def remote(*args):
            received.append(args);return '/remote/codex/uploads/file.png'
        self.bridge.uploads = Uploads(self.root/'remote-data', remote)
        self.bridge.host = self.fixture.host = 'remote:test'
        file = self.upload('image.png', PNG)
        self.bridge.send(THREAD, 'Read image', str(uuid.uuid4()), attachments=[file['id']])
        call = self.calls()[0]
        self.assertEqual(call['hostId'], 'remote:test')
        self.assertEqual(call['params']['turnStart']['request']['input'][1]['path'], '/remote/codex/uploads/file.png')
        with patch('bridge.remote.ssh_read', return_value={'path':'/remote/path'}) as ssh:
            self.assertEqual(upload_file('test-host', *received[0]), '/remote/path')
            self.assertEqual(ssh.call_args.args[0], 'test-host')
            compile(ssh.call_args.args[1], '<remote-upload>', 'exec')

    def test_activity_follows_visible_chats_without_history_reads_or_activation(self):
        self.bridge.list()
        with patch.object(self.bridge.store, 'history', side_effect=AssertionError('No history for list')), patch('bridge.service.open_in_desktop', side_effect=AssertionError('No navigation')):
            rows = self.bridge.activity([THREAD, str(uuid.uuid4())])
            self.assertEqual(len(rows), 1)
            session = self.bridge.live[THREAD]
            with session.condition:
                self.assertTrue(session.condition.wait_for(lambda: session.connected, timeout=3))
            self.fixture.state['threadRuntimeStatus']['type'] = 'active'
            self.fixture.state['turns'] = [{'turnId': 'run', 'status': 'inProgress', 'items': []}]
            self.fixture.revision += 1;self.fixture.snapshot()
            with session.condition: self.assertTrue(session.condition.wait_for(lambda: session.state['threadRuntimeStatus']['type']=='active', timeout=2))
            self.assertEqual(self.bridge.activity([THREAD])[0]['status'], 'active')
            self.bridge._disconnected()
            self.assertEqual(self.bridge.activity([THREAD])[0]['status'], 'unknown')

    def test_opening_activity_only_session_still_loads_saved_prefix(self):
        self.fixture.state['turnsPagination'] = {'hasLoadedOldest': False}
        self.bridge.list();self.bridge.activity([THREAD]);session=self.bridge.live[THREAD]
        with session.condition: self.assertTrue(session.condition.wait_for(lambda: session.connected, timeout=3))
        with patch.object(self.bridge.store, 'history', return_value={**support.state(), 'turns':[{'turnId':'old','status':'completed','items':[{'type':'agentMessage','id':'reply','text':'Saved prefix'}]}]}):
            self.bridge.session(THREAD, background=True)
            with session.condition: self.assertTrue(session.condition.wait_for(lambda: session.saved_view is not None, timeout=2))
        self.assertEqual(session.view()['turns'][0]['id'], 'old')

    def test_cold_activity_session_loads_history_without_waiting_for_subscription_retry(self):
        self.fixture.loaded = False
        self.bridge.list();self.bridge.activity([THREAD]);session=self.bridge.live[THREAD]
        session.retry_at = time.monotonic() + 15
        with patch.object(self.bridge.store, 'history', return_value=support.state()), patch('bridge.service.open_in_desktop', side_effect=AssertionError('No navigation')):
            self.bridge.session(THREAD, background=True)
            with session.condition: self.assertTrue(session.condition.wait_for(lambda: session.saved_view is not None, timeout=2))
        self.assertFalse(session.connected)


class UploadHttpTests(unittest.TestCase):
    setUpClass = classmethod(support.HttpTests.setUpClass.__func__)
    setUp = support.HttpTests.setUp
    tearDown = support.HttpTests.tearDown
    request = support.HttpTests.request
    login = support.HttpTests.login

    def raw(self, headers):
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=3)
        self.addCleanup(conn.close)
        conn.request('POST', '/api/sessions/'+THREAD+'/uploads?id='+str(uuid.uuid4())+'&name=image.png', body=PNG, headers={'Origin':self.origin,'Content-Type':'application/octet-stream', **headers})
        response=conn.getresponse();return response.status,json.loads(response.read())

    def test_raw_binary_upload_requires_login_csrf_and_preserves_bytes(self):
        received=[]
        self.server.bridge.upload=lambda *args: received.append(args) or {'id':args[1],'name':args[2],'size':len(args[3]),'image':'image/png'}
        self.assertEqual(self.raw({})[0],401)
        credentials=self.login()
        self.assertEqual(self.raw({'Cookie':credentials['Cookie']})[0],403)
        status,result=self.raw(credentials)
        self.assertEqual(status,200);self.assertEqual(result['size'],len(PNG));self.assertEqual(received[0][-1],PNG)
        self.assertEqual(len(received),1)
