import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare_upstream_sync', ROOT/'scripts/prepare-upstream-sync.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class UpstreamSyncTests(unittest.TestCase):
    def test_dirty_tree_and_wrong_origin_stop_before_fetch_or_merge(self):
        for answers in ([' M bridge/notifications.py'], ['', 'https://github.com/other/repo.git']):
            with self.subTest(answers=answers), patch.object(sync, 'git', side_effect=answers) as git:
                with self.assertRaises(ValueError):
                    sync.prepare()
                self.assertTrue(all(call.args[0] in ('status', 'remote') for call in git.call_args_list))

    def test_no_incoming_commits_does_not_switch_merge_commit_or_push(self):
        with patch.object(sync, 'git', side_effect=['', sync.ORIGIN, sync.UPSTREAM, '', '', '']) as git:
            self.assertIn('Already includes', sync.prepare())
        self.assertFalse(any(call.args[0] in ('switch', 'merge', 'commit', 'push') for call in git.call_args_list))
