#!/usr/bin/env python3
"""Build a locally signed, upgradeable APK; never prints or commits signing secrets."""
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
android = root / 'android'
signing = root / '.local/android-signing'
signing.mkdir(parents=True, exist_ok=True, mode=0o700)
signing.chmod(0o700)
credentials = signing / 'credentials.json'
key_exists = (signing/'codex-mobile.jks').exists()
new_credentials = not credentials.exists()
if key_exists and new_credentials:
    raise RuntimeError('Signing key exists without credentials; restore its original credentials backup.')
if not key_exists and not new_credentials:
    raise RuntimeError('Signing key is missing; restore .local/android-signing from your backup.')
if new_credentials:
    fd = os.open(credentials, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as file:
        json.dump({'alias': 'codex-mobile', 'storePassword': secrets.token_urlsafe(40)}, file)
credentials.chmod(0o600)
secret = json.loads(credentials.read_text())
env = os.environ.copy()
env.update(CMB_ANDROID_KEYSTORE=str(signing / 'codex-mobile.jks'),
           CMB_ANDROID_STORE_PASSWORD=secret['storePassword'],
           CMB_ANDROID_KEY_PASSWORD=secret['storePassword'],
           CMB_ANDROID_KEY_ALIAS=secret['alias'])
java = Path(env['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in env else None
keytool = str(java / 'keytool') if java else 'keytool'
if not Path(env['CMB_ANDROID_KEYSTORE']).exists():
    subprocess.run([keytool, '-genkeypair', '-noprompt', '-keystore', env['CMB_ANDROID_KEYSTORE'],
                    '-storetype', 'JKS', '-storepass:env', 'CMB_ANDROID_STORE_PASSWORD',
                    '-keypass:env', 'CMB_ANDROID_KEY_PASSWORD', '-alias', secret['alias'],
                    '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10950',
                    '-dname', 'CN=Codex Mobile Bridge Android, OU=Local distribution'], env=env, check=True)
    Path(env['CMB_ANDROID_KEYSTORE']).chmod(0o600)
subprocess.run([str(android/('gradlew.bat' if os.name == 'nt' else 'gradlew')), '-p', str(android), ':app:assembleRelease'], env=env, check=True)
output = root / 'dist/android'
output.mkdir(parents=True, exist_ok=True)
version = re.search(r'versionName\s*=\s*"([^"]+)"', (android/'app/build.gradle.kts').read_text()).group(1)
apk = output / f'Codex-Mobile-Bridge-{version}.apk'
shutil.copyfile(android/'app/build/outputs/apk/release/app-release.apk', apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(output/'SHA256SUMS').write_text(f'{digest}  {apk.name}\n')
print(f'APK: {apk}\nSHA-256: {digest}')
print('Backup the complete .local/android-signing directory securely; keep it outside Git.')
