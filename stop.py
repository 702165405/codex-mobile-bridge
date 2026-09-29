#!/usr/bin/env python3
"""Stop the background instance launched for this project, if it still matches."""
import os
import signal
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent
pid_file = root / '.local/gateway.pid'
if not pid_file.exists():
    raise SystemExit('没有本项目的后台进程记录。前台启动的服务请在对应终端按 Ctrl+C。')
pid = int(pid_file.read_text().strip())
result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'uid=', '-o', 'command='], capture_output=True, text=True)
info = result.stdout.strip().split(None, 1)
if len(info) != 2 or info[0] != str(os.getuid()) or str(root / 'run.py') not in info[1]:
    raise SystemExit('记录的进程已退出或不再匹配；未停止任何进程。')
os.kill(pid, signal.SIGINT)
pid_file.unlink()
print('已请求停止本项目的手机网关。')
