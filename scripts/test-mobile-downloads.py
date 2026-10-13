#!/usr/bin/env python3
"""Test Android downloads against isolated HTTP servers; no gateway credentials."""
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
work = root / '.tmp/mobile-download-tests'
work.mkdir(parents=True, exist_ok=True)
java_home = os.environ.get('JAVA_HOME')
local_jdks = list((root / '.tmp/mobile-tools/jdk').glob('*/Contents/Home'))
if not java_home and local_jdks:
    java_home = str(local_jdks[0])
def java_tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else shutil.which(name)
subprocess.run([java_tool('javac'), '-encoding', 'UTF-8', '-d', str(work),
                str(root / 'mobile/android/src/io/github/try2love/codexbridge/GatewayURL.java'),
                str(root / 'mobile/android/src/io/github/try2love/codexbridge/MobileRelease.java'),
                str(root / 'mobile/android/src/io/github/try2love/codexbridge/ArtifactDownload.java'),
                str(root / 'mobile/android/src/io/github/try2love/codexbridge/UpdateInstallSession.java'),
                str(root / 'mobile/tests/UpdateDownloadTests.java'),
                str(root / 'mobile/tests/UpdateInstallSessionTests.java'),
                str(root / 'mobile/tests/GatewayUpdateDownloadTests.java'),
                str(root / 'mobile/tests/ArtifactDownloadTests.java'),
                str(root / 'mobile/tests/ArtifactRangeTests.java')], check=True)
keystore = work / 'fixture.p12'
if not keystore.exists():
    subprocess.run([java_tool('keytool'), '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA',
                    '-storetype', 'PKCS12', '-keystore', str(keystore), '-storepass', 'fixture-test',
                    '-keypass', 'fixture-test', '-dname', 'CN=127.0.0.1', '-ext', 'SAN=IP:127.0.0.1',
                    '-validity', '3650', '-noprompt'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for test in ('ArtifactDownloadTests', 'ArtifactRangeTests', 'UpdateDownloadTests', 'UpdateInstallSessionTests', 'GatewayUpdateDownloadTests'):
    options = []
    if test == 'GatewayUpdateDownloadTests':
        options = [f'-Djavax.net.ssl.keyStore={keystore}', '-Djavax.net.ssl.keyStorePassword=fixture-test',
                   f'-Djavax.net.ssl.trustStore={keystore}', '-Djavax.net.ssl.trustStorePassword=fixture-test']
    subprocess.run([java_tool('java'), *options, '-cp', str(work), test, str(work / test)], check=True)
