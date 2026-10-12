import hashlib
import http.client
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

from bridge.features.updates.mobile import MobileUpdates, API, asset_name, asset_url, redirect_allowed, ReleaseRedirect
from urllib.request import Request
import test_bridge as support

PAYLOAD = b'Android fixture package' * 6000
SHA = hashlib.sha256(PAYLOAD).hexdigest()
VALUE = {'version': '2.0.3', 'size': len(PAYLOAD), 'sha256': SHA}


def row(version='2.0.3', **extra):
    return {'tag_name': 'v' + version, 'draft': False, 'prerelease': '-' in version, 'body': 'Fixture',
            'assets': [{'name': asset_name(version), 'browser_download_url': asset_url(version),
                        'size': len(PAYLOAD), 'digest': 'sha256:' + SHA}], **extra}


class Response(io.BytesIO):
    status = 200
    def __init__(self, data):
        super().__init__(data)
        self.headers = {'Content-Length': str(len(data))}


def fixture_service(directory):
    service = MobileUpdates(directory)
    service._open = Mock(side_effect=lambda url: Response(json.dumps([row()]).encode() if url == API else PAYLOAD))
    return service


def completed(service, job):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        state = service.status(job['id'])
        if state['state'] != 'downloading':
            return state
        time.sleep(.01)
    raise AssertionError('download did not terminate')


class MobileUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=support.ROOT / '.tmp')
        self.service = fixture_service(self.temp.name)

    def tearDown(self):
        self.service.close()
        self.temp.cleanup()

    def test_channel_selection_and_metadata_cache(self):
        self.service._open = Mock(return_value=Response(json.dumps([row('2.0.4-beta.1'), row(), row('2.0.5', draft=True)]).encode()))
        self.assertEqual(self.service.check('2.0.2')['release']['version'], '2.0.3')
        self.assertIsNone(self.service.check('2.0.3')['release'])
        self.assertEqual(self.service.check('2.0.3-beta.1')['release']['version'], '2.0.4-beta.1')
        self.service._open.assert_called_once_with(API)
        with self.assertRaises(ValueError): self.service.check('../etc')

    def test_no_arbitrary_destination_and_exact_release_identity(self):
        for value in ({**VALUE, 'url': 'http://127.0.0.1/private'}, {**VALUE, 'version': '../x'},
                      {**VALUE, 'sha256': '0' * 64}, {**VALUE, 'size': True}, {**VALUE, 'version': '2.0.4'}):
            with self.assertRaises(ValueError): self.service.prepare(value)
        self.assertTrue(all(call.args[0] == API for call in self.service._open.call_args_list))

    def test_unverifiable_new_release_is_not_reported_as_up_to_date(self):
        missing = row()
        missing['assets'][0]['digest'] = None
        self.service._open = Mock(return_value=Response(json.dumps([missing, row('2.0.1')]).encode()))
        with self.assertRaisesRegex(ValueError, '校验信息'): self.service.check('2.0.2')
        self.assertIsNone(self.service.check('2.0.4')['release'])

    def test_cache_range_resume_and_concurrent_reuse(self):
        job = self.service.prepare(VALUE)
        self.assertEqual(completed(self.service, job)['state'], 'ready')
        self.assertEqual(self.service.prepare(VALUE)['id'], job['id'])
        self.assertEqual(sum(c.args[0] == asset_url('2.0.3') for c in self.service._open.call_args_list), 1)
        with self.service.download(job['id'], 'bytes=10-99') as (meta, stream):
            self.assertEqual(meta['status'], 206)
            self.assertEqual(stream.read(meta['length']), PAYLOAD[10:100])
        with self.service.download(job['id'], 'bytes=100-199', meta['etag']) as (next_meta, stream):
            self.assertEqual(next_meta['status'], 206)
            self.assertEqual(stream.read(next_meta['length']), PAYLOAD[100:200])
        with self.service.download(job['id'], 'bytes=100-199', '"stale"') as (meta, stream):
            self.assertEqual(meta['status'], 200)
        with self.service.download(job['id'], 'bytes=999999-') as (meta, stream):
            self.assertEqual(meta['status'], 416)
        self.service.jobs[job['id']]['used'] = 0
        with self.assertRaises(ValueError): self.service.status(job['id'])
        self.service._cleanup()
        self.assertEqual(list(self.service.directory.iterdir()), [])

    def test_only_verified_package_is_servable_and_failed_task_can_retry(self):
        self.service._open.side_effect = lambda url: Response(json.dumps([row()]).encode() if url == API else b'x' * len(PAYLOAD))
        job = self.service.prepare(VALUE)
        self.assertEqual(completed(self.service, job)['state'], 'failed')
        with self.assertRaises(ValueError):
            with self.service.download(job['id']): pass
        self.assertFalse((self.service.directory / (job['id'] + '.apk')).exists())
        self.service._open.side_effect = lambda url: Response(PAYLOAD)
        retry = self.service.prepare(VALUE)
        self.assertEqual(completed(self.service, retry)['state'], 'ready')

    def test_download_is_shared_and_does_not_hold_http_request(self):
        started, resume = threading.Event(), threading.Event()
        service = self.service
        def read(url):
            if url == API: return Response(json.dumps([row()]).encode())
            started.set();resume.wait(2);return Response(PAYLOAD)
        service._open.side_effect = read
        job = service.prepare(VALUE)
        self.assertEqual(job['state'], 'downloading')
        self.assertTrue(started.wait(1))
        self.assertEqual(service.prepare(VALUE)['id'], job['id'])
        resume.set()
        self.assertEqual(completed(service, job)['state'], 'ready')

    def test_incomplete_http_body_finishes_as_failure(self):
        self.service.releases()
        self.service._open.side_effect = http.client.IncompleteRead(b'partial')
        self.assertEqual(completed(self.service, self.service.prepare(VALUE))['state'], 'failed')

    def test_retry_cannot_start_before_old_staging_cleanup(self):
        entered, resume = threading.Event(), threading.Event()
        unlink = Path.unlink
        def slow_unlink(path, *args, **kwargs):
            if threading.current_thread().name == 'mobile-update' and path.suffix == '.part':
                entered.set();resume.wait(2)
            return unlink(path, *args, **kwargs)
        self.service._open.side_effect = lambda url: Response(json.dumps([row()]).encode() if url == API else b'x' * len(PAYLOAD))
        with patch.object(Path, 'unlink', slow_unlink):
            job = self.service.prepare(VALUE)
            self.assertTrue(entered.wait(1))
            retry = self.service.prepare(VALUE)
            self.assertEqual(retry['state'], 'downloading')
            self.assertEqual(sum(c.args[0] == asset_url('2.0.3') for c in self.service._open.call_args_list), 1)
            resume.set()
            self.assertEqual(completed(self.service, job)['state'], 'failed')
        self.service._open.side_effect = lambda url: Response(PAYLOAD)
        self.assertEqual(completed(self.service, self.service.prepare(VALUE))['state'], 'ready')

    def test_release_redirect_rules(self):
        for url in ('http://github.com/x', 'https://github.com.evil.test/x', 'https://user@github.com/x', 'https://github.com:8443/x'):
            self.assertFalse(redirect_allowed(url))
        self.assertTrue(redirect_allowed('https://release-assets.githubusercontent.com/fixture'))
        with self.assertRaises(ValueError):
            ReleaseRedirect().redirect_request(Request(API), None, 302, '', {}, 'https://github.com/x')


class MobileUpdateHttpTests(unittest.TestCase):
    setUpClass = classmethod(support.HttpTests.setUpClass.__func__)
    request = support.HttpTests.request
    login = support.HttpTests.login

    def setUp(self):
        support.HttpTests.setUp(self)
        self.temp = tempfile.TemporaryDirectory(dir=support.ROOT / '.tmp')
        self.server.mobile_updates = fixture_service(self.temp.name)

    def tearDown(self):
        support.HttpTests.tearDown(self)
        self.server.mobile_updates.close()
        self.temp.cleanup()

    def test_auth_csrf_methods_and_update_download(self):
        check, prepare = '/api/mobile/update?current=2.0.2', '/api/mobile/update/prepare'
        self.assertEqual(self.request('GET', check)[0], 401)
        self.assertEqual(self.request('POST', prepare, VALUE)[0], 401)
        headers = self.login()
        self.assertEqual(self.request('POST', prepare, VALUE, {'Cookie': headers['Cookie']})[0], 403)
        self.assertEqual(self.request('GET', prepare, headers=headers)[0], 405)
        self.assertEqual(self.request('GET', check + '&url=http://localhost', headers=headers)[0], 400)
        self.assertEqual(self.request('GET', check, headers=headers)[2]['release']['version'], '2.0.3')
        job = self.request('POST', prepare, VALUE, headers)[2]
        self.assertEqual(completed(self.server.mobile_updates, job)['state'], 'ready')
        path = '/api/mobile/update/packages/' + job['id']
        self.assertEqual(self.request('GET', path)[0], 401)
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=3)
        conn.request('GET', path, headers={**headers, 'Range': 'bytes=10-99'})
        response = conn.getresponse()
        self.assertEqual(response.status, 206)
        self.assertEqual(response.getheader('Content-Type'), 'application/vnd.android.package-archive')
        self.assertEqual(response.read(), PAYLOAD[10:100])
        conn.close()
        self.server.auth.config['mode'] = 'none'
        self.assertEqual(self.request('GET', check, headers=headers)[0], 403)
