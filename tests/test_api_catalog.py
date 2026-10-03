import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from bridge.catalog import Catalog

ROOT = Path(__file__).resolve().parents[1]


class ApiCatalogTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT/'.tmp')
        self.home = Path(self.tmp.name)
        self.reader = Catalog(self.home, 'fixture-runtime')
        (self.home/'auth.json').write_text(json.dumps({'auth_mode':'apikey','OPENAI_API_KEY':'fixture-key'}))
        self.config = {'model_provider':'bridge_api','model_providers':{'bridge_api':{
            'name':'Fixture','base_url':'https://fixture.invalid/v1','requires_openai_auth':True}}}
        self.calls = []

    def tearDown(self):
        self.tmp.cleanup()

    def request(self, method, params):
        self.calls.append((method,params))
        if method == 'config/read':return {'config':self.config}
        if method == 'account/read':return {'account':{'type':'apiKey'}}
        if method == 'model/list':return {'data':[{'model':'gpt-fixture'}], 'nextCursor':None}
        raise AssertionError(method)

    def test_api_uses_upstream_gemini_ids_instead_of_native_gpt_catalog(self):
        with patch('bridge.catalog.model_ids',return_value=['gemini-fixture']) as upstream:
            result = self.reader.read_models(self.request,str(self.home),'bridge_api')
        upstream.assert_called_once_with('https://fixture.invalid/v1','fixture-key')
        self.assertEqual([m['model'] for m in result['models']],['gemini-fixture'])
        self.assertEqual(result['modelSource'],'api')
        self.assertFalse(any(method=='model/list' for method,_ in self.calls))
        self.assertNotIn('fixture-key',json.dumps(result))

    def test_resumed_openai_provider_and_project_profile_resolve_their_own_endpoint(self):
        self.config.update(openai_base_url='https://openai-alias.invalid/v1',profile='project',profiles={
            'project':{'model_provider':'other','model_providers':{'other':{
                'base_url':'https://project.invalid/v1','experimental_bearer_token':'project-key'}}}})
        for provider,url,key in [('openai','https://openai-alias.invalid/v1','fixture-key'),(None,'https://project.invalid/v1','project-key')]:
            with patch('bridge.catalog.model_ids',return_value=['gemini-fixture']) as upstream:
                self.reader.read_models(self.request,str(self.home),provider)
                upstream.assert_called_once_with(url,key)

    def test_upstream_failure_never_falls_back_to_gpt_and_keeps_manual_entry(self):
        with patch('bridge.catalog.model_ids',side_effect=ValueError('无法连接上游，请检查 API 地址、网络和证书后重试')):
            result = self.reader.read_models(self.request,str(self.home),'bridge_api')
        self.assertEqual(result['models'],[])
        self.assertEqual(result['modelSource'],'api');self.assertIn('无法连接上游',result['modelError'])
        self.assertFalse(any(method=='model/list' for method,_ in self.calls))

    def test_official_account_retains_native_catalog(self):
        self.config={}
        def request(method,params):
            if method=='account/read':return {'account':{'type':'chatgpt'}}
            return self.request(method,params)
        with patch('bridge.catalog.model_ids') as upstream:
            result=self.reader.read_models(request,str(self.home),'openai')
        upstream.assert_not_called();self.assertEqual(result['models'][0]['model'],'gpt-fixture')

    def test_missing_env_key_and_extra_auth_do_not_send_partial_credentials(self):
        for definition in ({'env_key':'CMB_ABSENT_FIXTURE_KEY'}, {'http_headers':{'private':'fixture'}}):
            self.config['model_providers']['bridge_api'].update(definition)
            with patch('bridge.catalog.model_ids') as upstream:
                result=self.reader.read_models(self.request,str(self.home),'bridge_api')
            upstream.assert_not_called();self.assertEqual(result['models'],[]);self.assertTrue(result['modelError'])

    def test_provider_is_part_of_catalog_cache_identity(self):
        calls=[]
        def fetch(cwd,provider=None):
            calls.append(provider)
            return {'models':[{'model':provider}], 'skillEntries':[], 'modelSource':'api'}
        self.reader._fetch=fetch
        self.assertEqual(self.reader.get(self.home,provider='first')['models'][0]['id'],'first')
        self.assertEqual(self.reader.get(self.home,provider='second')['models'][0]['id'],'second')
        self.reader.get(self.home,provider='first');self.assertEqual(calls,['first','second'])

class RemoteCatalogTests(unittest.TestCase):
    def test_remote_source_includes_helpers_and_keeps_provider_cache_separate(self):
        from bridge.remote import RemoteCatalog
        sources=[]
        def read(alias,source,timeout):
            self.assertEqual(alias,'fixture-host');sources.append(source)
            # Compile and load the exact helper bundle without starting a runtime or SSH.
            definitions=source[:source.rindex('\nimport shutil\nhome=')]
            namespace={'__file__':'<stdin>'}
            exec(compile(definitions,'<remote-catalog>','exec'),namespace)
            self.assertTrue(callable(namespace['model_ids']))
            self.assertTrue(callable(namespace['client_context']))
            compile(source,'<remote-catalog>','exec')
            return {'models':[],'skills':[],'modelSource':'api'}
        reader=RemoteCatalog('fixture-host')
        with patch('bridge.remote.ssh_read',side_effect=read):
            reader.get('/project',provider='first');reader.get('/project',provider='second');reader.get('/project',provider='first')
        self.assertEqual(len(sources),2)
        self.assertNotEqual(sources[0],sources[1])
