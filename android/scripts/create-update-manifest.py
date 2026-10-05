#!/usr/bin/env python3
"""Generate the domain-hosted update manifest from the signed APK, without secrets."""
import argparse
import hashlib
import json
import re
from urllib.parse import urlsplit
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--notes', default='更新地址改为 mac.lqilt.top 域名，保留安装包校验和同签名升级。')
parser.add_argument('--base-url', default='https://mac.lqilt.top/android/')
args = parser.parse_args()
base = urlsplit(args.base_url)
if base.scheme != 'https' or not base.hostname or base.username or base.password or base.query or base.fragment:
    parser.error('--base-url must be an HTTPS directory URL without credentials, query or fragment')
root = Path(__file__).resolve().parents[2]
config = (root / 'android/app/build.gradle.kts').read_text()
version = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
code = int(re.search(r'versionCode\s*=\s*(\d+)', config).group(1))
apk = root / f'dist/android/Codex-Mobile-Bridge-{version}.apk'
manifest = dict(applicationId='io.github.codexmobilebridge.android', versionName=version, versionCode=code,
                sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                apkUrl=args.base_url.rstrip('/') + '/' + apk.name,
                notes=args.notes)
path = apk.parent / 'update.json'
path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(path)
