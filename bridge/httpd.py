"""Same-origin HTTP/SSE gateway. Desktop RPC is never exposed directly."""
import gzip
import hmac
import json
import logging
import mimetypes
import re
import secrets
import socket
import threading
import time
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from socketserver import TCPServer
from urllib.parse import parse_qs, urlsplit, quote

from .pairing import Pairing
from .auth import Auth
from .ipc import IPCError
from .catalog import CatalogError
from .store import StoreUnavailable
from .remote import RemoteUnavailable
from .create import CreationError

LOG = logging.getLogger(__name__)
STATIC = {"/": ("index.html", "text/html; charset=utf-8"),
          "/i18n.js": ("i18n.js", "text/javascript; charset=utf-8"),
          "/app.js": ("app.js", "text/javascript; charset=utf-8"),
          "/markdown.js": ("markdown.js", "text/javascript; charset=utf-8"),
          "/timeline.js": ("timeline.js", "text/javascript; charset=utf-8"),
          "/vendor/markdown-it.min.js": ("vendor/markdown-it.min.js", "text/javascript; charset=utf-8"),
          "/vendor/texmath.js": ("vendor/texmath.js", "text/javascript; charset=utf-8"),
          "/vendor/katex/katex.min.js": ("vendor/katex/katex.min.js", "text/javascript; charset=utf-8"),
          "/vendor/katex/katex.min.css": ("vendor/katex/katex.min.css", "text/css; charset=utf-8"),
          "/style.css": ("style.css", "text/css; charset=utf-8"),
          "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json"),
          "/icon.png": ("icon.png", "image/png")}
THREAD_ROUTE = re.compile(r"^/api/sessions/([0-9a-f-]{36})(?:/(events|send|stop|history|respond|reconnect|queue|catalog|settings|poll|timeline|changes|detail|notifications))?$")
FONT_ROUTE = re.compile(r"^/vendor/katex/fonts/(KaTeX_[A-Za-z0-9_-]+\.(woff2|woff|ttf))$")


class GatewayServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, bridge, config, web_dir):
        self.bridge = bridge
        self.notifications = None
        self.instance_id = secrets.token_hex(16)
        self.auth = Auth(config["auth"])
        self.origins = set(config["origins"])
        self.pairing = Pairing(self.auth, self.origins)
        self.hosts = {urlsplit(o).netloc for o in self.origins}
        self.secure_hosts = {urlsplit(o).netloc for o in self.origins if o.startswith("https://")}
        self.web_dir = Path(web_dir)
        self.slots = threading.BoundedSemaphore(48)
        super().__init__(address, Handler)

    def server_bind(self):
        # HTTPServer resolves the listening IP with getfqdn(), which can stall
        # startup on machines without working reverse DNS. Origins are explicit.
        TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        LOG.warning("HTTP connection failed")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CodexMobile/0.1"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, format, *args):
        # Request bodies, cookies, query strings and conversation IDs are private.
        pass

    def headers_common(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        # KaTeX emits inline layout styles; scripts and stylesheets stay same-origin.
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")

    def output(self, status, data, content_type="application/json; charset=utf-8", cookie=None):
        body = json.dumps(data, ensure_ascii=False).encode() if not isinstance(data, bytes) else data
        accepts_gzip = re.search(r'(?:^|,)\s*gzip\s*(?:;\s*q=([01](?:\.\d+)?))?\s*(?:,|$)',
                                 self.headers.get('Accept-Encoding', ''), re.IGNORECASE)
        compressible = content_type.startswith(('text/', 'application/json', 'image/svg+xml'))
        compressed = False
        if compressible and len(body) >= 1024 and accepts_gzip and float(accepts_gzip[1] or '1') > 0:
            candidate = gzip.compress(body, compresslevel=5, mtime=0)
            if len(candidate) < len(body):
                body, compressed = candidate, True
        self.send_response(status)
        self.headers_common()
        self.send_header("Content-Type", content_type)
        self.send_header('Vary', 'Accept-Encoding')
        if compressed:
            self.send_header('Content-Encoding', 'gzip')
        self.send_header("Content-Length", str(len(body)))
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(body)

    def cookie(self, token, clear=False):
        secure = "; Secure" if self.headers.get("Host") in self.server.secure_hosts else ""
        return f"{Auth.COOKIE}={token}; Path=/; HttpOnly; SameSite=Strict; Max-Age={0 if clear else 43200}{secure}"

    def token(self):
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get("Cookie", ""))
            return cookie[Auth.COOKIE].value if Auth.COOKIE in cookie else ""
        except Exception:
            return ""

    def check_request(self, write=False):
        if len(self.headers.get_all("Host", [])) != 1 or self.headers.get("Host") not in self.server.hosts:
            raise PermissionError("此访问地址未在网关配置中允许")
        origin = self.headers.get("Origin")
        if (origin and origin not in self.server.origins) or (write and not origin):
            raise PermissionError("不允许跨站请求")
        if self.headers.get("Sec-Fetch-Site") == "cross-site":
            raise PermissionError("不允许跨站请求")

    def authorized(self, write=False):
        session = self.server.auth.get(self.token())
        if not session:
            self.close_connection = True
            self.output(401, {"error": "请登录", "code": "unauthenticated"})
            return None
        if write and not hmac.compare_digest(self.headers.get("X-CSRF-Token", ""), session["csrf"]):
            raise PermissionError("登录验证已失效，请刷新页面")
        return session

    def read_json(self):
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("不支持分块请求体")
        if self.headers.get_content_type() != "application/json":
            raise ValueError("需要 JSON 请求")
        sizes = self.headers.get_all("Content-Length", [])
        if len(sizes) != 1 or not sizes[0].isdigit():
            raise ValueError("请求长度无效")
        size = int(sizes[0])
        if not 0 < size <= 512000:
            raise ValueError("请求过大或为空")
        value = json.loads(self.rfile.read(size))
        if not isinstance(value, dict):
            raise ValueError("请求内容必须为对象")
        return value

    def do_GET(self):
        self.handle_method(False)

    def do_POST(self):
        self.handle_method(True)

    def handle_method(self, write):
        try:
            self.check_request(write)
            path = urlsplit(self.path).path
            query = parse_qs(urlsplit(self.path).query)
            if not write and path in STATIC:
                name, content_type = STATIC[path]
                return self.output(200, (self.server.web_dir / name).read_bytes(), content_type)
            font = FONT_ROUTE.fullmatch(path)
            if not write and font:
                file = self.server.web_dir / 'vendor/katex/fonts' / font[1]
                if file.is_file():
                    return self.output(200, file.read_bytes(), 'font/'+font[2])
            if not write and path == "/api/auth":
                session = self.server.auth.get(self.token())
                return self.output(200, {"authenticated": bool(session), "csrf": session["csrf"] if session else None,
                                         "instanceId": self.server.instance_id,
                                         "notifications": self.server.notifications is not None,
                                         "passwordless": self.server.auth.config.get("mode") == "none",
                                         "transport": "poll" if self.headers.get("Host", "").endswith(".trycloudflare.com") else "sse"})
            if write and path == "/api/login":
                body = self.read_json()
                username, password = body.get("username", ""), body.get("password", "")
                if not isinstance(username, str) or not isinstance(password, str) or len(username) > 200 or len(password) > 1000:
                    raise ValueError("账号或密码格式不正确")
                token, session = self.server.auth.login(username, password, self.client_address[0])
                self.server.auth.logout(self.token())
                return self.output(200, {"csrf": session["csrf"]}, cookie=self.cookie(token))
            if write and path == '/api/pair':
                body = self.read_json()
                host = self.headers.get('Host', '')
                origin = ('https://' if host in self.server.secure_hosts else 'http://') + host
                if self.headers.get('Origin') != origin:
                    raise PermissionError('不允许的请求来源')
                token, session = self.server.pairing.exchange(body.get('token'), origin, self.client_address[0])
                self.server.auth.logout(self.token())
                return self.output(200, {'csrf': session['csrf']}, cookie=self.cookie(token))
            auth = self.authorized(write)
            if not auth:
                return
            if write and path == "/api/logout":
                self.read_json()
                self.server.auth.logout(self.token())
                return self.output(200, {"ok": True}, cookie=self.cookie("", clear=True))
            if not write and path == "/api/sessions":
                rows = self.server.bridge.list(query=query.get("q", [""])[0][:200], offset=max(0, int(query.get("offset", [0])[0])), archived=query.get("archived", ["false"])[0] == "true")
                return self.output(200, {"sessions": rows, "unavailableHosts": self.server.bridge.host_errors})
            if not write and path == '/api/projects':
                return self.output(200, {'projects': self.server.bridge.hosts.projects()})
            if write and path == '/api/sessions':
                body = self.read_json()
                return self.output(200, self.server.bridge.create_chat(body.get('project'), body.get('title'), body.get('id')))
            bridge = self.server.bridge.for_host(query.get("host", ["local"])[0])
            file_match = re.fullmatch(r"/api/sessions/([0-9a-f-]{36})/files/([a-f0-9]{64})", path)
            if not write and file_match:
                return self.download(bridge, *file_match.groups())
            match = THREAD_ROUTE.fullmatch(path)
            if not match:
                return self.output(404, {"error": "页面不存在"})
            thread_id, action = match.groups()
            if action == 'notifications':
                if self.server.notifications is None:
                    return self.output(200, {'available': False, 'watching': False})
                body = self.read_json() if write else {}
                return self.output(200, self.server.notifications.watch(thread_id, bridge.host, body.get('enabled') if write else None))
            if not write:
                if action == 'timeline':
                    return self.output(200, bridge.timeline_read(thread_id, limit=int(query.get('limit', ['20'])[0]), before=query.get('before', [None])[0]))
                if action == 'detail':
                    return self.output(200, bridge.timeline_read(thread_id, 'detail', cursor=query.get('key', [''])[0],
                                                               offset=int(query.get('offset', ['0'])[0]), version=query.get('version', [None])[0]))
                if action == 'changes':
                    return self.changes(bridge, thread_id, int(query.get('after', ['-1'])[0]),
                                        query.get('epoch', [''])[0], query.get('start', [''])[0])
                if action is None:
                    return self.output(200, bridge.view(thread_id, background=True))
                if action == "catalog":
                    return self.output(200, bridge.catalog(thread_id, refresh=query.get("refresh") == ["true"]))
                if action == "poll":
                    return self.poll(bridge, thread_id, int(query.get("after", ["-1"])[0]))
                if action == "events":
                    return self.stream(bridge, thread_id)
                return self.output(404, {"error": "接口不存在"})
            body = self.read_json()
            if action == "send":
                result = bridge.send(thread_id, body.get("text"), body.get("id", ""), body.get("mode", "send"), body.get("skills", []))
            elif action == "settings":
                result = bridge.settings(thread_id, body.get("model"), body.get("effort"))
            elif action == "stop":
                result = bridge.interrupt(thread_id)
            elif action == "history":
                result = bridge.full_history(thread_id)
            elif action == "respond":
                response = body.get("response")
                if not isinstance(response, dict):
                    raise ValueError("请求回应格式不正确")
                result = bridge.respond(thread_id, body.get("requestId"), response)
            elif action == "reconnect":
                if body.get('activate') is True:
                    session = bridge.activate(thread_id)
                    result = {'ok': True, 'connected': session.connected}
                elif body.get('progressive'):
                    bridge.session(thread_id, background=True, force=True)
                    result = {'ok': True}
                else:
                    result = bridge.view(thread_id, background=True, force=True)
            elif action == "queue":
                result = bridge.cancel_queued(thread_id, body.get("id", ""))
            else:
                return self.output(404, {"error": "接口不存在"})
            self.output(200, result)
        except PermissionError as exc:
            self.close_connection = True
            self.output(403, {"error": str(exc)})
        except KeyError:
            self.close_connection = True
            self.output(404, {"error": "找不到这个会话"})
        except ValueError as exc:
            self.close_connection = True
            self.output(400, {"error": str(exc)})
        except (IPCError, CatalogError, RemoteUnavailable, CreationError, StoreUnavailable) as exc:
            self.close_connection = True
            self.output(409, {"error": str(exc), "code": "desktop_unavailable"})
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            self.close_connection = True
        except Exception:
            LOG.exception("Gateway operation failed")
            self.close_connection = True
            self.output(500, {"error": "网关操作失败，请查看本机日志"})

    def download(self, bridge, thread_id, artifact_id):
        artifact = bridge.artifact(thread_id, artifact_id)
        data = artifact["path"].read_bytes()
        self.send_response(200)
        self.headers_common()
        mime = mimetypes.guess_type(artifact["name"])[0] if artifact["image"] else "application/octet-stream"
        self.send_header("Content-Type", mime or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        disposition = "inline" if artifact["image"] else "attachment"
        self.send_header("Content-Disposition", disposition + "; filename*=UTF-8''" + quote(artifact["name"]))
        self.end_headers()
        self.wfile.write(data)

    def poll(self, bridge, thread_id, after):
        session = bridge.session(thread_id, background=True)
        with session.condition:
            session.viewers += 1
        try:
            with session.condition:
                session.condition.wait_for(lambda: session.sequence != after or bridge.closed.is_set(), timeout=12)
                changed = session.sequence != after
            if not self.authorized():
                return
            self.output(200, {"state": bridge.view(thread_id, attach=False, background=True) if changed else None})
        finally:
            with session.condition:
                session.viewers -= 1
                session.touched = time.monotonic()

    def changes(self, bridge, thread_id, after, epoch, start):
        session = bridge.session(thread_id, background=True)
        with session.condition:
            session.viewers += 1
        try:
            with session.condition:
                session.condition.wait_for(lambda: session.sequence != after or epoch != session.timeline.epoch or bridge.closed.is_set(), timeout=12)
            if self.authorized():
                self.output(200, bridge.timeline_read(thread_id, 'changes', after=after, epoch=epoch, start=start))
        finally:
            with session.condition:
                session.viewers -= 1
                session.touched = time.monotonic()

    def stream(self, bridge, thread_id):
        session = bridge.session(thread_id, background=True)
        token = self.token()
        with session.condition:
            session.viewers += 1
        self.send_response(200)
        self.headers_common()
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        sequence = -1
        try:
            while not bridge.closed.is_set():
                if not self.server.auth.get(token):
                    self.wfile.write(b'event: logout\ndata: {}\n\n')
                    self.wfile.flush()
                    break
                with session.condition:
                    session.condition.wait_for(lambda: session.sequence != sequence or bridge.closed.is_set(), timeout=12)
                    updated = session.sequence != sequence
                    sequence = session.sequence
                if updated:
                    view = bridge.view(thread_id, attach=False, background=True)
                    payload = json.dumps(view, ensure_ascii=False, separators=(",", ":"))
                    self.wfile.write(f"id: {sequence}\nevent: state\ndata: {payload}\n\n".encode())
                else:
                    self.wfile.write(b": heartbeat\n\n")
                self.wfile.flush()
                bridge.closed.wait(0.2)
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            pass
        finally:
            with session.condition:
                session.viewers -= 1
                session.touched = time.monotonic()
