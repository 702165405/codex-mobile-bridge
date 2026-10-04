#!/usr/bin/env python3
"""Install Android build tools locally (.tmp), leaving other projects untouched."""
import os
from pathlib import Path
import platform
import shutil
import subprocess
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[2]
local = root / '.tmp/android-tools'
sdk = local / 'sdk'
manager = sdk / 'cmdline-tools/latest/bin/sdkmanager'
system = {'Darwin': 'mac', 'Linux': 'linux', 'Windows': 'win'}[platform.system()]
if system == 'win':
    manager = manager.with_name('sdkmanager.bat')
if not manager.exists():
    local.mkdir(parents=True, exist_ok=True)
    archive = local / 'commandline.zip'
    with urllib.request.urlopen(f'https://dl.google.com/android/repository/commandlinetools-{system}-13114758_latest.zip', timeout=120) as response, archive.open('wb') as output:
        shutil.copyfileobj(response, output)
    with zipfile.ZipFile(archive) as file:
        file.extractall(local)
    (sdk/'cmdline-tools').mkdir(parents=True, exist_ok=True)
    shutil.move(str(local/'cmdline-tools'), sdk/'cmdline-tools/latest')
    if system != 'win':
        for file in manager.parent.iterdir():
            file.chmod(0o755)
env = os.environ.copy()
env['ANDROID_USER_HOME'] = str(root/'.tmp/android-user')
Path(env['ANDROID_USER_HOME']).mkdir(parents=True, exist_ok=True)
subprocess.run([str(manager), f'--sdk_root={sdk}', '--licenses'], input='y\n'*100, text=True, env=env, check=True)
subprocess.run([str(manager), f'--sdk_root={sdk}', 'platform-tools', 'platforms;android-35', 'build-tools;35.0.0'], env=env, check=True)
# Java .properties needs escaped separators/backslashes on Windows.
value = str(sdk).replace('\\', '\\\\').replace(':', '\\:')
(root/'android/local.properties').write_text(f'sdk.dir={value}\n')
print(f'Project-local Android SDK: {sdk}')
