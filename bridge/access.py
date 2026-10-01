"""Fixed-domain setup files and validation; never installs or configures a server."""
import http.client
import ipaddress
import json
import re
from urllib.parse import urlsplit


DEFAULTS = {'accessMode': 'lan', 'publicUrl': '', 'proxyUpstream': '',
            'sshTarget': '', 'sshRemotePort': 18787}


def origin(value, scheme='https'):
    if not isinstance(value, str) or any(c.isspace() for c in value):
        raise ValueError('地址不能包含空白字符')
    parsed = urlsplit(value)
    if (parsed.scheme != scheme or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment):
        raise ValueError(f'请填写完整 {scheme.upper()} 地址，不含路径、账号或参数')
    host = parsed.hostname
    try:
        ipaddress.ip_address(host)
        if '%' in host:
            raise ValueError('地址不能包含 IPv6 区域标识')
    except ValueError:
        if not re.fullmatch(r'[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?', host):
            raise ValueError('请使用域名或 IP 地址；国际化域名请填写 Punycode')
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError('地址端口不正确')
    authority = '['+host+']' if ':' in host else host
    if parsed.port is not None and parsed.port != (443 if scheme == 'https' else 80):
        authority += ':'+str(parsed.port)
    return f'{scheme}://{authority.lower()}'


def validate(preferences):
    p = {**DEFAULTS, **preferences}
    if p['accessMode'] not in ('lan', 'quick', 'server', 'nas'):
        raise ValueError('请选择有效的外网连接方式')
    if p['publicUrl']:
        p['publicUrl'] = origin(p['publicUrl'])
    if p['proxyUpstream']:
        p['proxyUpstream'] = origin(p['proxyUpstream'], 'http')
    if (not isinstance(p['sshTarget'], str) or len(p['sshTarget']) > 253
            or (p['sshTarget'] and not re.fullmatch(r'[\w][\w.@-]*', p['sshTarget']))):
        raise ValueError('SSH 目标请填写已有 Host 别名或 user@hostname，不含空格和命令参数')
    port = p['sshRemotePort']
    if isinstance(port, bool) or not isinstance(port, int) or not 1024 <= port <= 65535:
        raise ValueError('服务器回环端口须为 1024–65535')
    if p['accessMode'] in ('server', 'nas') and not p['publicUrl']:
        raise ValueError('请填写手机访问的固定 HTTPS 地址')
    if p['accessMode'] == 'server':
        if not p['sshTarget']:
            raise ValueError('请填写服务器的 SSH 目标')
        if urlsplit(p['publicUrl']).port not in (None, 443):
            raise ValueError('服务器自动 HTTPS 方案使用 443 端口；已有其他入口请选择 NAS / 已有反代')
    if p['accessMode'] == 'nas':
        if not p['lan'] or not p['proxyUpstream']:
            raise ValueError('NAS 方式需要开启局域网访问并填写 NAS 可达的电脑 HTTP 地址')
        parsed = urlsplit(p['proxyUpstream'])
        if (parsed.port or 80) != p['port']:
            raise ValueError('电脑上游地址的端口须与网关监听端口一致')
        if parsed.hostname in ('localhost', '127.0.0.1', '::1'):
            raise ValueError('NAS 上游请使用电脑的局域网地址，不能使用回环地址')
    p['tunnel'] = p['accessMode'] == 'quick'
    return p


def public_url(preferences):
    return preferences.get('publicUrl', '') if preferences.get('accessMode') in ('server', 'nas') else ''


def deployment(preferences):
    p = validate(preferences)
    if p['accessMode'] not in ('server', 'nas'):
        raise ValueError('请先选择并保存自有服务器或 NAS 配置')
    url = p['publicUrl']
    upstream = f'http://127.0.0.1:{p["sshRemotePort"]}' if p['accessMode'] == 'server' else p['proxyUpstream']
    common = f'''# Codex 手机网关 · 固定入口部署

手机地址：{url}/
电脑网关端口：{p['port']}（保持原值）
反代上游：{upstream}

电脑、网关和原 Codex App 需要持续运行。Docker 仅部署 HTTPS 入口。
本包不包含账号密码、Token、Codex 数据或 SSH 私钥。
网关登录密码从电脑 App 的“查看首次登录凭据”获取，或由用户自行设置。
域名必须指向服务器/NAS，手机使用独立子域名根路径，不支持 /codex/ 子路径。
登录建议使用账号密码。不要将电脑端口直接映射到公网。

'''
    if p['accessMode'] == 'server':
        files = {
            'compose.yaml': '''services:
  codex-https:
    image: caddy:2-alpine
    restart: unless-stopped
    network_mode: host
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
volumes:
  caddy_data:
  caddy_config:
''',
            'Caddyfile': f'''{urlsplit(url).hostname} {{
    reverse_proxy {upstream} {{
        header_up Host {{http.request.host}}
        flush_interval -1
    }}
}}
'''}
        steps = f'''## 自有 Linux 服务器（Docker Engine）

1. 确认电脑终端可使用 `ssh {p['sshTarget']}` 连接目标服务器。首次连接由用户核对主机指纹；后台连接需要已有 SSH 密钥或已解锁的 ssh-agent。App 不存储服务器密码，也不会跳过主机验证。
2. 服务器须允许远程端口转发（AllowTcpForwarding remote 或 yes），GatewayPorts 使用 no 或 clientspecified。回环端口 {p['sshRemotePort']} 须空闲。不要使用 GatewayPorts yes，以免强制向公网绑定。
3. 将域名 DNS 的 A/AAAA 记录指向这台服务器。将本包解压到一个新目录；服务器须已安装 Docker Compose，且公网 TCP 80、443 可达并空闲。已有网站占用这两个端口时，不要停止旧网站；使用已有反代，将上游设为 {upstream} 并保留公网 Host 即可，无需启动本包的 Caddy。
4. 在电脑 App 保存配置并启动网关。电脑主动建立 SSH 回程连接，不要求服务器能直接访问电脑的局域网 IP。
5. 在服务器解压目录执行：

```sh
docker compose config
docker compose up -d
docker compose logs --tail=50
```

Caddy 自动申请和续期 HTTPS 证书，证书保存在 Docker 卷中。此包的 host 网络仅面向 Linux Docker Engine；SSH 转发和 Caddy 必须位于同一台主机。不要在 Mac/Windows 的 Docker Desktop 运行此服务器包。
'''
    else:
        files = {
            'compose.yaml': '''services:
  codex-entry:
    image: nginx:stable-alpine
    restart: unless-stopped
    ports:
      - "${BIND_ADDRESS:-127.0.0.1}:18787:8080"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
''',
            'nginx.conf': f'''server {{
    listen 8080;
    server_name _;
    client_max_body_size 512000;
    access_log off;
    location / {{
        proxy_pass {upstream};
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }}
}}
''',
            '.env.example': '# 外层反代运行在 NAS 主机上时保持默认。若反代也在容器内，填写 NAS 局域网 IP，并限制仅反代可访问。\nBIND_ADDRESS=127.0.0.1\n'}
        steps = f'''## NAS / 已有 HTTPS 反代

1. 先在 NAS 确认可以访问 {upstream}/。电脑与 NAS 必须网络互通；家中 NAS 无法直接访问校园网电脑时，改用 App 的“自有服务器 + SSH”或先建立可用的 VPN 路由。
2. 推荐直接在群晖、威联通或 Nginx Proxy Manager 创建 HTTPS 反代：公网入口 {url}，目标 {upstream}。使用已有证书，保留外部 Host、Origin、Cookie、X-CSRF-Token，关闭缓存和缓冲，读写超时设为 300 秒。
3. 若希望用 Docker 统一管理 HTTP 转发，将本包放在 NAS 新目录执行：

```sh
docker compose config
docker compose up -d
docker compose logs --tail=50
```

然后把 NAS 的 HTTPS 反代上游改为 http://127.0.0.1:18787。每层都需保留外部 Host。若外层反代也在容器内，复制 .env.example 为 .env，将 BIND_ADDRESS 填为 NAS 局域网 IP，外层上游也用该 IP:18787；限制仅该反代可访问，禁止公网映射此端口。
4. Docker 容器只提供 HTTP 中转，证书和公网 443 由 NAS 已有反代负责。没有现成 HTTPS 入口时，可选择自有 Linux 服务器方案。
'''
    files['部署说明.md'] = common + steps + f'''
## 验收与维护

电脑 App 点击“检测固定入口”，然后手机关闭 Wi-Fi，用蜂窝网络打开 {url}/，验证登录、聊天同步和 ntfy 跳转。检测仅验证这台电脑经 HTTPS 访问的是当前网关，不替代手机外网测试。
502/504：检查反代上游或 SSH 隧道；403：检查每层保留 Host，以及电脑配置保存后是否重启；证书错误：检查 DNS、80/443 和证书日志。
启动/更新容器：docker compose up -d；日志：docker compose logs --tail=50；停止入口：docker compose down（不删除证书卷，不停止电脑网关）。

## 给部署 Agent

项目：https://github.com/try2love/codex-mobile-bridge
仅使用用户授权的服务器/NAS；执行前确认现有站点、端口和 DNS，禁止覆盖已有服务。部署依赖缺失时先说明。不要复制 Codex 凭据或私钥，不更改电脑网关端口。完成后返回固定 HTTPS 地址、原局域网地址、密码获取方式、部署目录、启停命令、已通过和未通过的验收项。没有权限或真实入口时，说明尚未部署，不声称连通。
'''
    return files


def read_auth(url):
    parsed = urlsplit(url)
    cls = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
    connection = cls(parsed.hostname, parsed.port, timeout=8)
    try:
        connection.request('GET', '/api/auth', headers={'Cache-Control': 'no-cache'})
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError(f'入口返回 HTTP {response.status}；检查反代上游、Host 和 HTTPS 配置')
        value = json.loads(response.read(65536))
        if not isinstance(value, dict):
            raise ValueError('入口返回的不是网关响应，请检查反代目标')
        return value
    finally:
        connection.close()


def check_entry(preferences):
    url = public_url(preferences)
    if not url:
        raise ValueError('请先保存固定 HTTPS 入口配置')
    local = read_auth(f'http://127.0.0.1:{preferences["port"]}')
    remote = read_auth(url)
    if not local.get('instanceId') or remote.get('instanceId') != local['instanceId']:
        raise ValueError('入口没有连接到当前网关；请检查反代目标，旧版网关须先更新并重启')
    return {'message': '固定 HTTPS 入口已连到当前网关。请再用手机蜂窝网络验证登录与聊天。', 'url': url}
