#!/usr/bin/env python3
"""Verify the actual macOS distribution, including its bundled Python runtime.

This checks signature integrity, not Developer ID trust or notarization.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path


def verify(app):
    subprocess.run(['codesign', '--verify', '--deep', '--strict', '--verbose=2', str(app)], check=True)
    runtime = app/'Contents/Resources/gateway/codex-mobile-gateway'
    subprocess.run(['codesign', '--verify', '--strict', '--verbose=2', str(runtime)], check=True)
    subprocess.run([str(runtime), '--help'], check=True, stdout=subprocess.DEVNULL, timeout=30)
    print('PASS: app signature, nested code and bundled runtime; notarization not checked.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path, help='The distributed ZIP or an extracted .app')
    artifact = parser.parse_args().artifact.resolve()
    if sys.platform != 'darwin':
        parser.error('This check requires macOS')
    if artifact.suffix == '.app':
        verify(artifact)
        return
    if artifact.suffix != '.zip' or not artifact.is_file():
        parser.error('Expected an existing .app or ZIP')
    temporary = Path(__file__).resolve().parents[1]/'.tmp'
    temporary.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='macos-package-', dir=temporary) as directory:
        subprocess.run(['ditto', '-x', '-k', str(artifact), directory], check=True)
        apps = list(Path(directory).glob('*.app'))
        if len(apps) != 1:
            raise RuntimeError('The ZIP must contain exactly one top-level .app')
        verify(apps[0])


if __name__ == '__main__':
    main()
