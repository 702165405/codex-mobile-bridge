"""Cooperative local shutdown; never signal a PID that may have been reused."""
import json
import os
import re
import shutil
import tempfile
import secrets
import threading
import time
from pathlib import Path


def read_record(path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


class GatewayControl:
    def __init__(self, data_dir):
        self.path = Path(data_dir) / 'gateway-control.json'
        self.request_path = Path(data_dir) / 'gateway.stop'
        self.record = {'pid': os.getpid(), 'token': secrets.token_hex(32)}
        self.closed = threading.Event()
        self.worker = None

    def start(self, shutdown, pairing=None, instance_id=None, auth=None):
        self.pairing_dir = Path(tempfile.mkdtemp(prefix=".pairing-", dir=self.path.parent))
        self.record.update(pairingDir=self.pairing_dir.name, instanceId=instance_id, deviceManagement=auth is not None)
        self.request_path.unlink(missing_ok=True)
        self.path.write_text(json.dumps(self.record), encoding='utf-8')
        self.path.chmod(0o600)
        def watch():
            while not self.closed.wait(.2):
                if read_record(self.request_path) == self.record:
                    shutdown()
                    return
                if pairing:
                    for request in self.pairing_dir.glob('*.request'):
                        value = read_record(request)
                        request.unlink(missing_ok=True)
                        if not isinstance(value, dict) or value.get('control') != self.record:
                            continue
                        try:
                            payload = value.get('payload', {})
                            if payload.get('action') == 'devices' and auth:
                                result = {'ok': True, 'result': auth.manage(payload.get('value', {}))}
                            else:
                                result = {'ok': True, 'result': pairing(payload)}
                        except (ValueError, TypeError, OSError) as exc:
                            result = {'ok': False, 'error': str(exc)}
                        private_json(request.with_suffix('.response'), result)
        self.worker = threading.Thread(target=watch, daemon=True)
        self.worker.start()

    def close(self):
        self.closed.set()
        if self.worker:
            self.worker.join(timeout=2)
        if hasattr(self, "pairing_dir"):
            shutil.rmtree(self.pairing_dir, ignore_errors=True)
        for path in (self.path, self.request_path):
            if read_record(path) == self.record:
                path.unlink(missing_ok=True)


def request_stop(data_dir, timeout=15):
    path = Path(data_dir) / 'gateway-control.json'
    record = read_record(path)
    if not isinstance(record, dict) or not isinstance(record.get('pid'), int) or not record.get('token'):
        raise RuntimeError('No gateway control record; use Ctrl+C in its terminal if it is running.')
    request = path.with_name('gateway.stop')
    request.write_text(json.dumps(record), encoding='utf-8')
    request.chmod(0o600)
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            if read_record(path) != record:
                return
            time.sleep(.1)
        raise RuntimeError('Gateway did not acknowledge shutdown; the record may be stale. No process was killed.')
    finally:
        if read_record(request) == record:
            request.unlink(missing_ok=True)


def private_json(path, value):
    temporary = path.with_suffix('.tmp')
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as stream:
        json.dump(value, stream)
    temporary.replace(path)


def request_pairing(data_dir, payload, timeout=5):
    path = Path(data_dir) / 'gateway-control.json'
    record = read_record(path)
    name = record.get('pairingDir', '') if isinstance(record, dict) else ''
    if not re.fullmatch(r'\.pairing-[a-zA-Z0-9_-]+', name):
        raise ValueError('请重新启动网关以启用扫码登录')
    directory = path.parent / name
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError('网关已停止')
    identifier = secrets.token_hex(16)
    request = directory / (identifier + '.request')
    response = request.with_suffix('.response')
    try:
        private_json(request, {'control': record, 'payload': payload})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if read_record(path) != record:
                raise ValueError('网关已停止')
            result = read_record(response)
            if isinstance(result, dict):
                if not result.get('ok'):
                    raise ValueError(result.get('error', '扫码登录操作失败'))
                return result['result']
            time.sleep(.05)
        raise ValueError('扫码登录操作超时，请重试')
    finally:
        request.unlink(missing_ok=True)
        response.unlink(missing_ok=True)
