#!/usr/bin/env python3
"""Build this fork's integrated Android debug app using its Gradle project."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
wrapper = root/'android'/('gradlew.bat' if os.name == 'nt' else 'gradlew')
subprocess.run([str(wrapper), '-p', str(root/'android'), ':app:assembleDebug'], cwd=root, check=True)
print(root/'android/app/build/outputs/apk/debug/app-debug.apk')
print('For a same-signature upgrade APK, run android/scripts/build-release.py.')
