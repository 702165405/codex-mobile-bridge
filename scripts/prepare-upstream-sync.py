#!/usr/bin/env python3
"""Combine local and remote work on a review branch; never push or modify main."""
import subprocess
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ORIGIN = 'https://github.com/702165405/codex-mobile-bridge.git'
UPSTREAM = 'https://github.com/try2love/codex-mobile-bridge.git'


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True, encoding='utf-8').strip()


def prepare():
    if git('status', '--porcelain'):
        raise ValueError('Working tree is not clean. Commit or preserve your changes before syncing.')
    if git('remote', 'get-url', 'origin') != ORIGIN or git('remote', 'get-url', 'upstream') != UPSTREAM:
        raise ValueError('Unexpected remotes. origin must be 702165405 and upstream must be try2love.')
    if git('branch', '--show-current') != 'main':
        raise ValueError('Switch to main and merge any feature work you want to keep before syncing.')
    git('fetch', 'upstream', 'main')
    git('fetch', 'origin', 'main')
    remote_commits = git('log', '--oneline', 'main..origin/main')
    commits = git('log', '--oneline', 'main..upstream/main')
    if not remote_commits and not commits:
        return 'Already includes origin/main and upstream/main; no review branch created.'
    branch = 'codex/sync-upstream-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    git('switch', '-c', branch, 'main')
    # Keep unpublished local commits. Divergent fork history may create a merge
    # commit here, on the review branch only; conflicts stop before upstream.
    git('merge', '--no-edit', 'origin/main')
    # Conflicts deliberately remain on this review branch for manual resolution.
    git('merge', '--no-ff', '--no-commit', 'upstream/main')
    changes = git('diff', 'main', '--stat')
    report = ROOT/'.tmp'/('upstream-review-' + branch.split('/')[-1] + '.md')
    report.parent.mkdir(exist_ok=True)
    report.write_text('# Upstream review required\n\nBranch: ' + branch + '\n\n## Fork remote commits\n\n```\n' + remote_commits +
                      '\n```\n\n## Upstream commits\n\n```\n' + commits +
                      '\n```\n\n## Changed files\n\n```\n' + changes + '\n```\n\nReview security-sensitive changes and fork fixes. '
                      'Run Python, desktop and updater tests. Commit and open a PR only after review; never auto-merge.\n', encoding='utf-8')
    return 'Review branch: ' + branch + '\nReport: ' + str(report) + '\nNo push or main update performed; review before merging.'


if __name__ == '__main__':
    try:
        print(prepare())
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
