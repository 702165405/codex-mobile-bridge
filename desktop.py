#!/usr/bin/env python3
"""Private stdio interface used by the desktop application."""
import argparse
import json
import sys
from bridge.desktop import Desktop


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stdin.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['snapshot', 'save', 'start', 'stop', 'logs', 'test-notification', 'serve'])
    parser.add_argument('--data-dir', required=True)
    args = parser.parse_args()
    desktop = Desktop(args.data_dir)
    if args.action == 'serve':
        import run
        sys.argv = [sys.argv[0], *desktop.argv()]
        run.main()
        return
    try:
        if args.action == 'save':
            result = desktop.save(json.loads(sys.stdin.read(100000)))
        else:
            method = {'test-notification': desktop.test_notification}.get(args.action) or getattr(desktop, args.action)
            result = method()
        print(json.dumps({'ok': True, 'result': result}, ensure_ascii=False))
    except Exception as exc:
        print(json.dumps({'ok': False, 'error': str(exc)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == '__main__':
    main()
