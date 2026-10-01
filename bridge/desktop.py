"""Local desktop controller. No management API is exposed to the network."""
import http.client
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import zipfile
from pathlib import Path

from .auth import password_record
from .lifecycle import read_record, request_stop
from .notifications import read_json, write_json, settings, save_settings, publish
from . import access


class Desktop:
    def __init__(self, data_dir):
        self.data_dir = Path(data_dir).expanduser().resolve()
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.config_path = self.data_dir/'config.json'

    def config(self):
        if not self.config_path.exists():
            password = secrets.token_urlsafe(18)
            write_json(self.config_path, {'auth': {'mode': 'password', 'username': 'admin', **password_record(password)}, 'origins': []})
            credentials = self.data_dir/'首次登录.txt'
            credentials.write_text('Codex App 手机网关\n账号：admin\n密码：'+password+'\n', encoding='utf-8')
            credentials.chmod(0o600)
        return read_json(self.config_path, {})

    def preferences(self):
        name = 'cloudflared.exe' if os.name == 'nt' else 'cloudflared'
        executable = self.data_dir/'bin'/name
        defaults = {'autoStart': False, 'port': 8787, 'lan': True, 'tunnel': (self.data_dir/'外网地址.txt').exists(),
                    'cloudflared': str(executable) if executable.is_file() else shutil.which(name) or '',
                    'codexHome': os.environ.get('CODEX_HOME', str(Path.home()/'.codex')),
                    'ipcPath': '', 'codexBin': ''}
        saved = read_json(self.data_dir/'desktop.json', {})
        rows = access.connections({**defaults, **saved})
        return {**defaults, **{k: v for k, v in saved.items() if k not in access.DEFAULTS},
                'connections': rows, 'tunnel': any(c['enabled'] and c['accessMode'] == 'quick' for c in rows)}

    def status(self):
        preferences = self.preferences()
        connected, supports = False, False
        try:
            connection = http.client.HTTPConnection('127.0.0.1', preferences['port'], timeout=1)
            try:
                connection.request('GET', '/api/auth')
                response = connection.getresponse()
                payload = json.loads(response.read(65536))
                connected = response.status == 200 and 'authenticated' in payload and 'passwordless' in payload
                supports = bool(payload.get('notifications'))
            finally:
                connection.close()
        except (OSError, ValueError, http.client.HTTPException):
            pass
        record = read_record(self.data_dir/'gateway-control.json')
        managed = connected and bool(record)
        return {'running': managed, 'portOccupied': connected and not managed, 'supportsNotifications': supports,
                'pid': record.get('pid') if managed else None}

    def snapshot(self):
        config = self.config()
        preferences = self.preferences()
        runtime = self.status()
        from run import addresses
        hosts = addresses() if preferences['lan'] else ['127.0.0.1']
        urls = [f'http://{host}:{preferences["port"]}/' for host in hosts if host != 'localhost']
        public = self.data_dir/'外网地址.txt'
        if runtime['running'] and preferences['tunnel'] and public.exists():
            value = public.read_text(encoding='utf-8').splitlines()[0]
            if value.startswith('https://'):
                urls.insert(0, value)
        urls = [url+'/' for url in access.public_urls(preferences)] + urls
        external = {}
        for entry in preferences['connections']:
            status = read_json(self.data_dir/('ssh-status-'+entry['id']+'.json'), {})
            if runtime['running'] and status.get('pid') == runtime.get('pid'):
                external[entry['id']] = status
        notifications = settings(self.data_dir)
        return {'preferences': preferences, 'auth': {'username': config['auth'].get('username', 'admin'), 'mode': config['auth']['mode']},
                'origins': config.get('origins', []), 'notifications': {**notifications, 'token': '', 'hasToken': bool(notifications['token'])},
                'watches': read_json(self.data_dir/'notification-watches.json', []),
                'notificationStatus': read_json(self.data_dir/'notification-status.json', {}),
                'dataDir': str(self.data_dir), 'credentialsAvailable': (self.data_dir/'首次登录.txt').exists(),
                'runtime': runtime, 'urls': urls, 'externalStatus': external}

    def save(self, value):
        old_preferences = self.preferences()
        preferences = {**old_preferences, **{k: v for k, v in value['preferences'].items() if k in old_preferences}}
        preferences['connections'] = access.validate_connections(preferences)
        preferences['tunnel'] = any(c['enabled'] and c['accessMode'] == 'quick' for c in preferences['connections'])
        port = preferences['port']
        if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
            raise ValueError('端口必须为 1–65535')
        for key in ('lan', 'tunnel', 'autoStart'):
            if not isinstance(preferences[key], bool):
                raise ValueError('网络开关格式不正确')
        for key in ('codexHome', 'codexBin', 'ipcPath', 'cloudflared'):
            if not isinstance(preferences[key], str) or '\x00' in preferences[key] or len(preferences[key]) > 4096:
                raise ValueError('路径格式不正确')
        if not Path(preferences['codexHome']).expanduser().is_dir():
            raise ValueError('Codex 数据目录不存在')
        if self.status()['running'] and any(preferences[k] != old_preferences[k] for k in preferences if k != 'autoStart'):
            raise ValueError('请先停止网关再更改网络或运行路径，以免正在使用的地址失效')
        config = self.config()
        auth = value['auth']
        if auth.get('mode') not in ('password', 'none') or not isinstance(auth.get('username'), str) or not auth['username'].strip() or len(auth['username']) > 200:
            raise ValueError('请填写有效的登录账号和登录方式')
        password = auth.get('password', '')
        if not isinstance(password, str) or (password and not 12 <= len(password) <= 1000):
            raise ValueError('新密码至少需要 12 个字符')
        origins = value.get('origins', [])
        if not isinstance(origins, list):
            raise ValueError('额外 HTTPS 源格式不正确')
        origins = list(dict.fromkeys(access.origin(origin) for origin in origins))
        old_fixed = access.public_urls(old_preferences)
        origins = [origin for origin in origins if origin not in old_fixed]
        for fixed in access.public_urls(preferences):
            if fixed not in origins:
                origins.append(fixed)
        if self.status()['running'] and origins != config.get('origins', []):
            raise ValueError('请先停止网关再更改 HTTPS 地址，保存后重新启动')
        # Validate all values before writing any setting.
        notification_value = value.get('notifications', {})
        # save_settings performs the remaining validation; settings files have separate owners.
        save_settings(self.data_dir, notification_value)
        config['auth'].update(mode=auth['mode'], username=auth['username'].strip())
        if password:
            config['auth'].update(password_record(password))
        config['origins'] = origins
        config['publicUrl'] = access.public_url(preferences)
        config['connections'] = preferences['connections']
        write_json(self.config_path, config)
        write_json(self.data_dir/'desktop.json', preferences)
        if password:
            (self.data_dir/'首次登录.txt').unlink(missing_ok=True)
        return self.snapshot()

    def argv(self):
        p = self.preferences()
        args = ['--config', str(self.config_path), '--port', str(p['port']), '--codex-home', str(Path(p['codexHome']).expanduser())]
        for flag, key in [('--lan', 'lan'), ('--tunnel', 'tunnel')]:
            if p[key]:
                args.append(flag)
        for flag, key in [('--cloudflared', 'cloudflared'), ('--ipc-path', 'ipcPath'), ('--codex-bin', 'codexBin')]:
            if p[key]:
                args.extend([flag, p[key]])
        return args

    def start(self):
        self.config()
        state = self.status()
        if state['running']:
            return {'started': False, 'message': '已连接正在运行的网关'}
        preferences = self.preferences()
        if not Path(preferences['codexHome']).expanduser().is_dir():
            raise ValueError('请先选择存在的 Codex 数据目录')
        if preferences['tunnel'] and not Path(preferences['cloudflared']).is_file():
            raise ValueError('请先选择 cloudflared 程序，或关闭临时外网入口')
        access.validate_connections(preferences)
        if any(c['enabled'] and c['accessMode'] == 'server' for c in preferences['connections']) and not shutil.which('ssh'):
            raise ValueError('未找到 OpenSSH 客户端；请先安装或启用系统 SSH 客户端')
        probe = socket.socket()
        if os.name != "nt":
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('0.0.0.0' if preferences['lan'] else '127.0.0.1', preferences['port']))
        except OSError as exc:
            raise ValueError('端口已被占用，请检查现有网关；不会自动更换端口') from exc
        finally:
            probe.close()
        command = [sys.executable] if getattr(sys, 'frozen', False) else [sys.executable, str(Path(__file__).resolve().parents[1]/'desktop.py')]
        command += ['serve', '--data-dir', str(self.data_dir)]
        kwargs = {'creationflags': subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
        # Frozen children run as independent applications, not multiprocessing workers.
        environment = {**os.environ, 'PYINSTALLER_RESET_ENVIRONMENT': '1'}
        with (self.data_dir/'gateway.log').open('ab') as log:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log, stderr=log, env=environment, **kwargs)
        return {'started': True, 'pid': process.pid, 'message': '正在启动网关'}

    def stop(self):
        request_stop(self.data_dir)
        return {'message': '网关已停止'}

    def test_notification(self):
        publish(settings(self.data_dir), 'Codex 手机通知测试', '收到这条消息表示 ntfy 通道已连通。')
        return {'message': 'ntfy 已接受测试通知，请在手机确认是否收到'}

    def deployment(self, value):
        files = access.deployment(access.select_connection(self.preferences(), value))
        return {'files': files}

    def export_deployment(self, value):
        files = access.deployment(access.select_connection(self.preferences(), value))
        path = self.data_dir/'固定入口部署.zip'
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in files.items():
                archive.writestr(name, content)
        path.chmod(0o600)
        return {'path': str(path)}

    def check_entry(self, value):
        if not self.status()['running']:
            raise ValueError('请先启动网关，再检测固定入口')
        return access.check_entry(access.select_connection(self.preferences(), value))

    def logs(self):
        # Reverse complete timestamped records, keeping traceback lines readable.
        import re
        paths = [('Gateway', self.data_dir/'gateway.log')]
        for entry in self.preferences()['connections']:
            if entry['accessMode'] == 'server':
                paths.append((entry['name'] or entry['id'], self.data_dir/('ssh-tunnel-'+entry['id']+'.log')))
        sections = []
        for label, path in paths:
            if not path.exists():
                continue
            with path.open('rb') as handle:
                offset = max(0, path.stat().st_size - 18000)
                handle.seek(offset)
                if offset:
                    handle.readline()
                text = handle.read().decode('utf-8', errors='replace')
            records = []
            for line in text.splitlines():
                continuation = line.startswith((' ', '\t', 'Traceback', 'During handling', 'The above')) or re.match(r'^[\w.]*(?:Error|Exception|Interrupt|Exit|Warning):', line)
                if not records or (line and not continuation):
                    records.append([line])
                else:
                    records[-1].append(line)
            sections.append({'name': label, 'text': '\n'.join('\n'.join(record) for record in reversed(records))})
        token = settings(self.data_dir).get('token')
        if token:
            for section in sections:
                section['text'] = section['text'].replace(token, '[REDACTED]')
        return {'sections': sections, 'text': '\n\n'.join('--- '+s['name']+' ---\n'+s['text'] for s in sections)}
