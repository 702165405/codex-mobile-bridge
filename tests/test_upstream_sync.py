import importlib.util
import subprocess
import tempfile
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
        with patch.object(sync, 'git', side_effect=['', sync.ORIGIN, sync.UPSTREAM, 'main', '', '', '', '']) as git:
            self.assertIn('Already includes', sync.prepare())
        self.assertFalse(any(call.args[0] in ('switch', 'merge', 'commit', 'push') for call in git.call_args_list))

    def test_fetch_failure_stops_before_switching_or_merging(self):
        for failed_fetch in ('upstream', 'origin'):
            def run(*args):
                if args[0] == 'status':
                    return ''
                if args[0] == 'remote':
                    return sync.ORIGIN if args[-1] == 'origin' else sync.UPSTREAM
                if args[0] == 'branch':
                    return 'main'
                if args == ('fetch', failed_fetch, 'main'):
                    raise subprocess.CalledProcessError(1, ['git', *args])
                return ''
            with self.subTest(remote=failed_fetch), patch.object(sync, 'git', side_effect=run) as git:
                with self.assertRaises(subprocess.CalledProcessError):
                    sync.prepare()
                self.assertFalse(any(call.args[0] in ('switch', 'merge', 'commit', 'push') for call in git.call_args_list))

    def test_review_preserves_divergent_local_and_remote_work_without_changing_main(self):
        for upstream_changes in (False, True):
            with self.subTest(upstream_changes=upstream_changes), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with patch.object(sync, 'ROOT', root):
                    git = sync.git
                    git('init', '-b', 'main')
                    git('config', 'user.name', 'Sync Test')
                    git('config', 'user.email', 'sync@example.invalid')
                    git('config', 'commit.gpgsign', 'false')
                    git('remote', 'add', 'origin', sync.ORIGIN)
                    git('remote', 'add', 'upstream', sync.UPSTREAM)

                    def commit_file(name):
                        (root/name).write_text(name, encoding='utf-8')
                        git('add', name)
                        git('commit', '-m', name)

                    commit_file('base.txt')
                    git('switch', '-c', 'source-work')
                    if upstream_changes:
                        commit_file('source.txt')
                    git('update-ref', 'refs/remotes/upstream/main', 'HEAD')
                    git('switch', '-c', 'remote-work', 'main')
                    commit_file('remote.txt')
                    git('update-ref', 'refs/remotes/origin/main', 'HEAD')
                    git('switch', 'main')
                    commit_file('local.txt')
                    original_main = git('rev-parse', 'main')

                    # Only transport is mocked; all branches and merges use real Git.
                    def offline_git(*args):
                        return '' if args[0] == 'fetch' else git(*args)

                    with patch.object(sync, 'git', side_effect=offline_git):
                        result = sync.prepare()
                    self.assertIn('Review branch:', result)
                    self.assertTrue(git('branch', '--show-current').startswith('codex/sync-upstream-'))
                    self.assertEqual(git('rev-parse', 'main'), original_main)
                    for name in ('local.txt', 'remote.txt') + (('source.txt',) if upstream_changes else ()):
                        self.assertEqual((root/name).read_text(encoding='utf-8'), name)
                    if upstream_changes:
                        git('commit', '-m', 'Reviewed upstream merge')
                    for ref in ('main', 'origin/main', 'upstream/main'):
                        git('merge-base', '--is-ancestor', ref, 'HEAD')
                    report = next((root/'.tmp').glob('upstream-review-*.md')).read_text(encoding='utf-8')
                    self.assertIn('remote.txt', report)
