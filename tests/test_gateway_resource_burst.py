"""A workbench resource burst must fit the gateway accept queue."""
import concurrent.futures
import http.client
import threading
import unittest

import test_bridge as support


class ResourceBurstTests(unittest.TestCase):
    def test_first_screen_resource_connections_do_not_reset(self):
        support.HttpTests.setUpClass()
        fixture = support.HttpTests()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        count = 40
        for _ in range(2):
            ready = threading.Barrier(count)

            def fetch(_):
                ready.wait(timeout=10)
                connection = http.client.HTTPConnection('127.0.0.1', fixture.port, timeout=3)
                try:
                    connection.request('GET', '/desktop-sessions.js', headers={'Connection': 'close'})
                    response = connection.getresponse()
                    body = response.read()
                    return response.status, response.getheader('Content-Type'), len(body)
                finally:
                    connection.close()

            with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
                rows = list(pool.map(fetch, range(count)))
            self.assertTrue(all(status == 200 and 'javascript' in mime and size > 0
                                for status, mime, size in rows))
