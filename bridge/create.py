"""Create an empty persisted thread, then hand execution to the desktop App.

The short-lived app-server never receives turn/start. It is shut down before
returning, so the desktop can become the sole execution owner.
"""
import json
import os
import queue
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode


class CreationError(RuntimeError):
    pass


def create_empty(executable, codex_home, cwd, title):
    if not executable:
        raise CreationError('找不到 Codex 运行时，请在电脑启动器的运行配置中指定路径')
    if not Path(cwd).is_dir():
        raise CreationError('项目目录不存在，请先在电脑 App 中检查项目')
    env = dict(os.environ, CODEX_HOME=str(codex_home))
    kwargs = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    process = subprocess.Popen([str(executable), 'app-server', '--listen', 'stdio://'],
                               cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, encoding='utf-8', **kwargs)
    messages = queue.Queue()

    def read():
        try:
            for line in process.stdout:
                try:
                    messages.put(json.loads(line))
                except ValueError:
                    continue
        finally:
            messages.put(None)

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    counter = 0

    def request(method, params):
        nonlocal counter
        counter += 1
        process.stdin.write(json.dumps({'id': counter, 'method': method, 'params': params}) + '\n')
        process.stdin.flush()
        deadline = time.monotonic() + 25
        while True:
            try:
                result = messages.get(timeout=max(.01, deadline - time.monotonic()))
            except queue.Empty as exc:
                raise CreationError('创建聊天超时，请先检查桌面聊天列表，不要重复创建') from exc
            if result is None:
                raise CreationError('创建聊天的运行时已断开，请先检查桌面聊天列表')
            if result.get('id') != counter:
                if time.monotonic() >= deadline:
                    raise CreationError('创建聊天超时，请先检查桌面聊天列表')
                continue
            if 'error' in result:
                raise CreationError(result['error'].get('message', '创建聊天失败'))
            return result['result']

    try:
        request('initialize', {'clientInfo': {'name': 'codex_mobile_bridge', 'version': '0.2'},
                               'capabilities': {'experimentalApi': True}})
        process.stdin.write('{"method":"initialized"}\n')
        process.stdin.flush()
        result = request('thread/start', {'cwd': cwd, 'ephemeral': False})
        thread_id = result['thread']['id']
        uuid.UUID(thread_id)
        request('thread/name/set', {'threadId': thread_id, 'name': title})
        # Empty threads defer rollout creation. Loading full history materializes
        # it without a model turn, allowing another App process to resume it.
        request('thread/read', {'threadId': thread_id, 'includeTurns': True})
    finally:
        # EOF flushes pending writes and releases the thread before App takeover.
        process.stdin.close()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise CreationError('创建运行时未正常退出，请先检查桌面聊天列表')
        finally:
            reader.join(timeout=1)
            process.stdout.close()
    if process.returncode:
        raise CreationError('创建运行时退出异常，请先检查桌面聊天列表')
    return thread_id


def open_in_desktop(thread_id, host):
    uuid.UUID(thread_id)
    url = 'codex://threads/' + thread_id
    if host != 'local':
        url += '?' + urlencode({'hostId': host})
    if sys.platform == 'darwin':
        subprocess.run(['open', url], check=True, timeout=10,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    elif os.name == 'nt':
        os.startfile(url)
    else:
        raise CreationError('请在支持 Codex App 的 Mac 或 Windows 电脑运行网关')
