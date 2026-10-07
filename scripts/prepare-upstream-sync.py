#!/usr/bin/env python3
"""Fetch upstream into a review branch; never commit, push or modify main."""
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
    git('fetch', 'origin', 'main')
    git('fetch', 'upstream', 'main')
    commits = git('log', '--oneline', 'origin/main..upstream/main')
    if not commits:
        return 'Already includes upstream/main; no review branch created.'
    branch = 'sync/upstream-' + datetime.now().strftime('%Y%m%d-%H%M%S')
    git('switch', '-c', branch, 'origin/main')
    # Conflicts deliberately remain on this review branch for manual resolution.
    git('merge', '--no-ff', '--no-commit', 'upstream/main')
    changes = git('diff', '--cached', '--stat')
    report = ROOT/'.tmp'/('upstream-review-' + branch.split('/')[-1] + '.md')
    report.parent.mkdir(exist_ok=True)
    report.write_text('# Upstream review required\n\nBranch: ' + branch + '\n\n## Incoming commits\n\n```\n' + commits +
                      '\n```\n\n## Changed files\n\n```\n' + changes + '\n```\n\nReview security-sensitive changes and fork fixes. '
                      'Run Python, desktop and updater tests. Commit and open a PR only after review; never auto-merge.\n', encoding='utf-8')
    return 'Review branch: ' + branch + '\nReport: ' + str(report) + '\nNo commit, push or main update performed.'


if __name__ == '__main__':
    try:
        print(prepare())
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
