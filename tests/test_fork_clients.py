"""The fork exposes exactly Codex and Claude Code, regardless of saved settings."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, Mock, AsyncMock
from bridge.clients.manager import DesktopSessions
from bridge.clients.claude.adapter import Claude
from bridge.clients.claude.model import gateway_id

class ForkClients(unittest.TestCase):
    def test_stored_deepseek_selection_cannot_enable_or_initialize_it(self):
        with tempfile.TemporaryDirectory() as root:
            manager=DesktopSessions(root)
            try:
                self.assertEqual(set(manager.adapters),{'claude'})
                manager.config['enabled']={'deepseek':True,'codex':True,'claude':True}
                self.assertFalse(manager.enabled('deepseek'))
                for value in ({'action':'toggle-client','provider':'deepseek','enabled':True},{'action':'connect-deepseek'},{'action':'install-deepseek','home':root}):
                    with self.assertRaises(ValueError):manager.control(value)
                with self.assertRaises(ValueError):manager.require_enabled('deepseek')
                self.assertEqual({row['id'] for row in manager.clients()['clients']},{'codex','claude'})
            finally:manager.close()

    def test_discovery_only_returns_supported_clients_and_does_not_launch_them(self):
        with tempfile.TemporaryDirectory() as root:
            manager=DesktopSessions(root)
            try:
                rows={key:{'id':key,'installed':False} for key in ('codex','claude','deepseek')}
                with patch('bridge.clients.discovery.discover_clients',return_value=rows),patch.object(manager.adapters['claude'],'connect') as connect:
                    result=manager.scan()
                self.assertEqual(set(manager.config['discovered']),{'codex','claude'})
                self.assertEqual({row['id'] for row in result['clients']},{'codex','claude'})
                connect.assert_not_called()
            finally:manager.close()

class ForkClaude(unittest.IsolatedAsyncioTestCase):
    async def test_cowork_is_neither_listed_nor_dispatchable(self):
        with tempfile.TemporaryDirectory() as root:
            adapter=Claude(Path(root),auto_connect=False)
            native=Mock(connected=True,capabilities={'code':['getAll'],'cowork':['getAll']})
            native.call=AsyncMock(return_value=[{'sessionId':'fixture','cwd':root}]);adapter.desktop=native
            value=await adapter.listing()
            self.assertTrue(value['complete']);self.assertEqual(value['coverage'],{'code':True})
            self.assertEqual([row['backend'] for row in value['sessions']],['code'])
            native.call.assert_awaited_once_with('code','getAll')
            with self.assertRaises(ValueError):await adapter.dispatch('detail',gateway_id('cowork','fixture'),{})
