"""Opt-in ntfy delivery. Watching a chat never changes its execution owner."""
import hashlib
import json
import re
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import quote, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, HTTPSHandler

from .model import pending_requests
from .tls import client_context

DEFAULTS = {'enabled': False, 'server': 'https://ntfy.sh', 'topic': '', 'token': '',
            'clickBase': '', 'includeTitle': False}


def read_json(path, default):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'))
    except FileNotFoundError:
        return default


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.chmod(0o600)
    temporary.replace(path)


def valid_url(value, allow_path=False):
    parsed = urlsplit(value)
    if (parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or (not allow_path and parsed.path not in ('', '/'))):
        raise ValueError('请输入完整的 HTTP/HTTPS 地址，不含账号、查询参数或片段')
    if parsed.scheme == 'http' and parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise ValueError('推送服务请使用 HTTPS；HTTP 仅用于本机测试')
    return value.rstrip('/')


def settings(data_dir):
    return {**DEFAULTS, **read_json(Path(data_dir)/'notifications.json', {})}


def save_settings(data_dir, value):
    prior = settings(data_dir)
    result = {**prior, **{k: value[k] for k in DEFAULTS if k in value}}
    result['server'] = valid_url(str(result['server']), allow_path=True)
    for key in ('enabled', 'includeTitle'):
        if not isinstance(result[key], bool):
            raise ValueError('通知开关格式不正确')
    if not isinstance(result['topic'], str) or (result['topic'] and not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', result['topic'])):
        raise ValueError('ntfy 主题只允许 1–64 位字母、数字、下划线和短横线')
    if result['enabled'] and not result['topic']:
        raise ValueError('开启通知前请填写 ntfy 主题')
    if result['clickBase']:
        # A link back to the existing LAN gateway may intentionally use HTTP.
        parsed = urlsplit(result['clickBase'])
        if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('通知跳转地址格式不正确')
        result['clickBase'] = result['clickBase'].rstrip('/')
    if not isinstance(result['token'], str) or len(result['token']) > 2000 or '\n' in result['token'] or '\r' in result['token']:
        raise ValueError('ntfy Token 格式不正确')
    # Blank fields from the UI preserve a token only for the same server.
    if not value.get('token'):
        result['token'] = '' if value.get('clearToken') or result['server'] != prior['server'] else prior['token']
    write_json(Path(data_dir)/'notifications.json', result)
    return result


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Do not forward a configured bearer token to another host.


def publish(config, title, body, click=''):
    if not config.get('topic'):
        raise ValueError('请先配置 ntfy 服务和主题')
    server = valid_url(config['server'], allow_path=True)
    payload = {'topic': config['topic'], 'title': title, 'message': body, 'tags': ['bell'], 'priority': 3}
    if click:
        payload['click'] = click
    headers = {'Content-Type': 'application/json'}
    if config.get('token'):
        headers['Authorization'] = 'Bearer ' + config['token']
    request = Request(server + '/', data=json.dumps(payload, ensure_ascii=False).encode(), headers=headers)
    with build_opener(NoRedirect(), HTTPSHandler(context=client_context())).open(request, timeout=8) as response:
        if not 200 <= response.status < 300:
            raise RuntimeError('ntfy 未接受通知')
        response.read(65536)


class Notifications:
    def __init__(self, bridge, data_dir, origins=lambda: [], public_url=lambda: ''):
        self.bridge, self.data_dir, self.origins = bridge, Path(data_dir), origins
        self.public_url = public_url
        self.lock = threading.RLock()
        self.closed = threading.Event()
        self.ledger = read_json(self.data_dir/'notification-delivery.json', {})
        self.attached = {}
        self.worker = None

    def start(self):
        self.worker = threading.Thread(target=self._run, daemon=True)
        self.worker.start()

    def close(self):
        self.closed.set()
        if self.worker:
            self.worker.join(timeout=2)
        for session in self.attached.values():
            session.watched = False

    def watches(self):
        return read_json(self.data_dir/'notification-watches.json', [])

    def watch(self, thread_id, host, enabled=None):
        uuid.UUID(thread_id)
        source = self.bridge.for_host(host)
        if enabled is not None:
            if not isinstance(enabled, bool):
                raise ValueError('提醒开关格式不正确')
            source.session(thread_id, background=True)
        with self.lock:
            config = settings(self.data_dir)
            rows = self.watches()
            selected = any(r['id'] == thread_id and r['host'] == host for r in rows)
            if enabled is not None:
                if enabled and not config['enabled']:
                    raise ValueError('请先在电脑启动器中配置并开启 ntfy 通知')
                rows = [r for r in rows if not (r['id'] == thread_id and r['host'] == host)]
                if enabled:
                    if len(rows) >= 100:
                        raise ValueError('最多关注 100 个聊天')
                    rows.append({'id': thread_id, 'host': host})
                write_json(self.data_dir/'notification-watches.json', rows)
                selected = enabled
            return {'available': config['enabled'], 'watching': selected}

    def click_url(self, config, thread_id, host):
        origins = sorted(self.origins())
        base = config.get('clickBase') or self.public_url() or next((o for o in origins if o.startswith('https://')), '')
        if not base:
            base = next((o for o in origins if urlsplit(o).hostname not in ('127.0.0.1', 'localhost')), '')
        return base.rstrip('/') + '/#' + thread_id + '~' + quote(host, safe='') if base else ''

    def scan(self):
        config = settings(self.data_dir)
        watches = self.watches() if config['enabled'] else []
        selected = {(r['host'], r['id']) for r in watches}
        for key in set(self.attached) - selected:
            self.attached.pop(key).watched = False
        changed = False
        for row in watches:
            if self.closed.is_set():
                break
            try:
                source = self.bridge.for_host(row['host'])
                session = source.session(row['id'], background=True)
                session.watched = True
                self.attached[(row['host'], row['id'])] = session
                with session.condition:
                    if not session.connected:
                        continue  # Saved history is not a live pending approval.
                    requests = pending_requests(session.state)
                    title = session.state.get('title') or '聊天'
                now = time.time()
                pending = []
                for request in requests:
                    key = hashlib.sha256(json.dumps([row['host'], row['id'], request['id']], ensure_ascii=False).encode()).hexdigest()
                    previous = self.ledger.get(key, {})
                    if not previous.get('delivered') and now >= previous.get('next', 0):
                        pending.append(key)
                if not pending:
                    continue
                heading = 'Codex 需要你的确认'
                body = f'有 {len(pending)} 项请求等待处理，请打开聊天查看。'
                if config['includeTitle']:
                    body = title[:120] + '\n' + body
                try:
                    publish(config, heading, body, self.click_url(config, row['id'], row['host']))
                    for key in pending:
                        self.ledger[key] = {'delivered': True, 'time': now}
                    self._status(lastSent=now, error='')
                except Exception:
                    for key in pending:
                        attempts = self.ledger.get(key, {}).get('attempts', 0) + 1
                        self.ledger[key] = {'delivered': False, 'attempts': attempts, 'next': now + min(300, 5 * 2 ** min(attempts, 6)), 'time': now}
                    self._status(error='ntfy 发送失败，将在请求仍待处理时重试。请检查服务地址、认证和网络。')
                changed = True
            except Exception:
                self._status(error='部分关注聊天暂时无法连接，请检查电脑 App 或 SSH 连接。')
        if changed:
            if len(self.ledger) > 5000:
                self.ledger = dict(sorted(self.ledger.items(), key=lambda p: p[1].get('time', 0))[-4000:])
            write_json(self.data_dir/'notification-delivery.json', self.ledger)

    def _status(self, **values):
        path = self.data_dir/'notification-status.json'
        write_json(path, {**read_json(path, {}), **values})

    def _run(self):
        while not self.closed.is_set():
            try:
                self.scan()
            except Exception:
                self._status(error='通知配置读取失败，请在电脑启动器重新保存配置。')
            self.closed.wait(3)
