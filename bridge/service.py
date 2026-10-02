import copy
import hashlib
from concurrent.futures import ThreadPoolExecutor
import json
import logging
import re
import subprocess
import threading
import time
import uuid
from pathlib import Path

from .ipc import DesktopIPC, IPCError
from .transport import ipc_endpoint
from .model import apply_patches, normalize_state, normalize_request, ordered_turns, async_requests
from .store import SessionStore, StoreUnavailable
from .files import artifact_paths
from .catalog import Catalog
from .remote import AppHosts, RemoteStore, RemoteCatalog, RemoteUnavailable, ssh_read, payload
from .create import create_empty, fork_copy, open_in_desktop, CreationError
from .timeline import Timeline
from .account import Account
from .uploads import Uploads
from .remote import upload_file


class LiveSession:
    def __init__(self, thread_id):
        self.id = thread_id
        self.owner = None
        self.discovering = False
        self.state = None
        self.saved_view = None
        self.revision = None
        self.sequence = 0
        self.connected = False
        self.connecting = False
        self.activating = False
        self.last_activation = None
        self.retry_at = 0
        self.error = None
        self.viewers = 0
        self.watched = False
        self.activity_only = False
        self.touched = time.monotonic()
        self.condition = threading.Condition(threading.RLock())
        self.attach_lock = threading.Lock()
        self.activation_lock = threading.Lock()
        self.action_lock = threading.Lock()
        self.timeline = Timeline()

    def changed(self):
        self.sequence += 1
        self.condition.notify_all()

    def set_history(self, state):
        self.saved_view = normalize_state(state, False)
        if self.state is None:
            self.state = state
        self.changed()

    def view(self):
        with self.condition:
            result = normalize_state(self.state or {"id": self.id}, self.connected)
            # Native paginated snapshots only contain the loaded tail. Keep the
            # saved prefix in the presentation, never in the IPC patch base.
            if self.saved_view and not result['historyComplete']:
                saved = self.saved_view['turns']
                turns = result['turns']
                first = next((i for i, turn in enumerate(saved) if turns and turn['id'] == turns[0]['id']), None)
                if not turns or first is not None:
                    result['turns'] = saved[:first] + turns if turns else saved
                    result['historyComplete'] = True
            result["sequence"] = self.sequence
            result["connectionError"] = self.error
            result["connecting"] = self.connecting
            result["activating"] = self.activating
            result["loadingHistory"] = self.state is None
            return result


class Bridge:
    def __init__(self, codex_home, data_dir, host="local", alias=None, ipc_path=None, codex_bin=None):
        self.host = host
        self.codex_home = Path(codex_home)
        self.data_dir = Path(data_dir)
        self.hosts = AppHosts(codex_home)
        self.remote_bridges = {}
        self.host_errors = []
        self.listed = set()
        self.activity_following = False
        self.store = RemoteStore(alias) if alias else SessionStore(codex_home)
        self.catalog_reader = RemoteCatalog(alias) if alias else Catalog(codex_home, codex_bin)
        self.uploads = Uploads(data_dir, (lambda *args: upload_file(alias, *args)) if alias else None)
        self.account = Account(codex_home, data_dir, codex_bin) if host == 'local' else None
        self.live = {}
        self.lock = threading.RLock()
        self.ipc = DesktopIPC(ipc_path or ipc_endpoint(codex_home), self._event, self._disconnected)
        self.closed = threading.Event()
        Path(data_dir).mkdir(parents=True, exist_ok=True, mode=0o700)
        self.ledger_path = Path(data_dir) / "submissions.json"
        self.submit_lock = threading.Lock()
        self.create_lock = threading.Lock()
        self.message_lock = threading.RLock()
        self.actions_path = self.data_dir / 'message-actions.json'
        self.message_actions = json.loads(self.actions_path.read_text(encoding='utf-8')) if self.actions_path.exists() else {}
        self.creations_path = self.data_dir / 'creations.json'
        self.creations = json.loads(self.creations_path.read_text(encoding='utf-8')) if self.creations_path.exists() else {}
        self.submissions = json.loads(self.ledger_path.read_text(encoding='utf-8')) if self.ledger_path.exists() else {}
        for key, value in self.submissions.items():
            if value.get("status") == "queued":
                self.live.setdefault(key.split(":")[0], LiveSession(key.split(":")[0]))
        threading.Thread(target=self._maintain, daemon=True).start()

    def _save_creations(self):
        target = self.creations_path.with_suffix('.tmp')
        target.write_text(json.dumps(self.creations, ensure_ascii=False), encoding='utf-8')
        target.chmod(0o600)
        target.replace(self.creations_path)

    def create_chat(self, project_key, title, request_id):
        if not isinstance(request_id, str):
            raise ValueError('创建请求标识无效')
        uuid.UUID(request_id)
        if not isinstance(title, str) or not title.strip() or len(title) > 120:
            raise ValueError('请输入 1–120 字的聊天名称')
        project = next((p for p in self.hosts.projects() if p['key'] == project_key), None)
        if project is None:
            raise ValueError('请选择电脑 App 中已保存的项目')
        title = title.strip()
        with self.create_lock:
            entry = self.creations.get(request_id)
            if entry:
                if entry['project'] != project_key or entry['title'] != title:
                    raise ValueError('同一创建请求不能用于不同内容')
                if not entry.get('id'):
                    raise CreationError('上次创建结果尚不确定，请先刷新聊天列表并检查电脑 App，避免重复创建')
            else:
                self.ipc.connect()
                entry = {'project': project_key, 'title': title, 'host': project['host'], 'at': time.time()}
                self.creations[request_id] = entry
                self._save_creations()
                if project['host'] == 'local':
                    thread_id = create_empty(self.catalog_reader.executable, self.codex_home, project['cwd'], title)
                else:
                    host = self.hosts.hosts()[project['host']]
                    source = Path(__file__).with_name('create.py').read_text(encoding='utf-8')
                    source += '\nimport shutil\nhome=Path(os.environ.get("CODEX_HOME", str(Path.home()/".codex")))\n'
                    source += 'runtime=shutil.which("codex") or str(Path.home()/".local/bin/codex")\n'
                    source += 'print(json.dumps({"id":create_empty(runtime, home, **' + payload({'cwd': project['cwd'], 'title': title}) + ')}))\n'
                    thread_id = ssh_read(host['alias'], source, timeout=90)['id']
                uuid.UUID(thread_id)
                entry['id'] = thread_id
                self._save_creations()
            bridge = self.for_host(entry['host'])
            if isinstance(bridge.store, RemoteStore):
                with bridge.store.lock:
                    bridge.store.cache.clear()
            try:
                open_in_desktop(entry['id'], entry['host'])
                opened = True
            except (OSError, CreationError, subprocess.SubprocessError):
                opened = False
            return {'id': entry['id'], 'host': entry['host'], 'opened': opened,
                    'message': '已创建，正在连接桌面 App' if opened else '聊天已创建，请在电脑 App 打开后重新连接'}

    def _save_actions(self):
        target = self.actions_path.with_suffix('.tmp')
        target.write_text(json.dumps(self.message_actions, ensure_ascii=False), encoding='utf-8')
        target.chmod(0o600)
        target.replace(self.actions_path)

    def _fork_origin(self, thread_id):
        with self.message_lock:
            entry = next((v for v in self.message_actions.values() if v.get('id') == thread_id and v['action'] != 'edit'), None)
            return {'id': entry['source'], 'title': entry['sourceTitle'], 'turnId': entry['turnId'], 'host': self.host} if entry else None

    @staticmethod
    def _fork_settings(state):
        result = {'modelProvider': state.get('modelProvider'), 'model': state.get('latestModel')}
        if not all(result.values()):
            raise ValueError('尚未取得原会话的模型配置，请重新连接')
        effort = state.get('latestReasoningEffort') or (state.get('latestThreadSettings') or {}).get('effort')
        if effort:
            result['config'] = {'model_reasoning_effort': effort}
        latest = state.get('latestThreadSettings') or {}
        if 'serviceTier' in latest:
            result['serviceTier'] = latest['serviceTier']
        permissions = state.get('currentPermissions') or {}
        profile = latest.get('activePermissionProfile', permissions.get('activePermissionProfile'))
        if profile and profile.get('id'):
            result['permissions'] = profile['id']
        for key in ('approvalPolicy', 'approvalsReviewer', 'runtimeWorkspaceRoots'):
            if key in permissions:
                result[key] = permissions[key]
        return result

    def message_action(self, thread_id, body):
        action, identifier = body.get('action'), body.get('id')
        if action not in ('edit', 'fork', 'edit-fork') or not isinstance(identifier, str):
            raise ValueError('消息操作无效')
        uuid.UUID(identifier)
        text = body.get('text') if action != 'fork' else None
        if action != 'fork' and (not isinstance(text, str) or not text.strip() or len(text) > 100000):
            raise ValueError('请输入 1–100000 字的消息')
        identity = {k: body.get(k) for k in ('action', 'key', 'version')}
        identity.update(source=thread_id, text=text)
        # Durable intent precedes every mutation. An uncertain native edit has no
        # idempotency key, so neither refresh nor a repeated POST may replay it.
        with self.message_lock:
            entry = self.message_actions.get(identifier)
            if entry:
                if any(entry.get(k) != v for k, v in identity.items()):
                    raise ValueError('同一操作标识不能用于不同内容')
                return self._action_result(entry)
            session = self._target(thread_id)
            with session.action_lock:
                with session.condition:
                    session.timeline.update(session.view())
                    position = session.timeline.position(body.get('key', ''))
                    if position is None:
                        raise ValueError('消息位置已变化，请刷新后重试')
                    row = session.timeline.rows[position]
                    if row['version'] != body.get('version'):
                        raise ValueError('消息内容已变化，请重新打开编辑')
                    if action == 'fork' and not row['forkable'] or action != 'fork' and not row['editable']:
                        raise ValueError('此消息不支持该操作')
                    if row.get('turnStatus') == 'inProgress':
                        raise ValueError('请先停止当前任务，再编辑或分支')
                    if action == 'edit':
                        if session.timeline.meta['latestUserTurnId'] != row['turnId']:
                            raise ValueError('只有最近一条消息可原地编辑，请使用编辑并新建分支')
                        if session.state.get('threadRuntimeStatus', {}).get('type') == 'active':
                            raise ValueError('请先停止当前任务，再编辑或分支')
                    snapshot = copy.deepcopy(session.state)
                    entry = {**identity, 'turnId': row['turnId'], 'sourceTitle': snapshot.get('title') or '未命名聊天',
                             'status': 'unknown', 'at': time.time()}
                    settings = self._fork_settings(snapshot) if action != 'edit' else None
                self.message_actions[identifier] = entry
                self._save_actions()
                if action != 'edit':
                    title = (entry['sourceTitle'][:90] + ' · 分支')
                    args = dict(cwd=snapshot['cwd'], source_id=thread_id, turn_id=entry['turnId'], title=title, settings=settings)
                    if self.host == 'local':
                        child = fork_copy(self.catalog_reader.executable, self.codex_home, **args)
                    else:
                        host = self.hosts.hosts()[self.host]
                        source = Path(__file__).with_name('create.py').read_text(encoding='utf-8')
                        source += '\nimport shutil\nhome=Path(os.environ.get("CODEX_HOME", str(Path.home()/".codex")))\n'
                        source += 'runtime=shutil.which("codex") or str(Path.home()/".local/bin/codex")\n'
                        source += 'print(json.dumps({"id":fork_copy(runtime, home, **' + payload(args) + ')}))\n'
                        child = ssh_read(host['alias'], source, timeout=120)['id']
                    uuid.UUID(child)
                    if child == thread_id:
                        raise CreationError('分支结果无效，请检查桌面聊天列表')
                    entry.update(id=child, status='created')
                    self._save_actions()
                    if isinstance(self.store, RemoteStore):
                        with self.store.lock:
                            self.store.cache.clear()
                    if action == 'fork':
                        return self._action_result(entry)
                    try:
                        target = self._target(child)
                    except (IPCError, OSError, CreationError, RemoteUnavailable):
                        # Creation succeeded; return the child and the unsent draft.
                        # A new edit operation on that child is safe after activation.
                        return self._action_result(entry)
                else:
                    target = session
                entry['status'] = 'unknown'
                self._save_actions()
                params = {'turnId': entry['turnId'], 'message': text, 'shouldSendPermissionOverrides': False}
                tier = (snapshot.get('latestThreadSettings') or {})
                if 'serviceTier' in tier:
                    params['serviceTier'] = tier['serviceTier']
                self._call(target, 'thread-follower-edit-last-user-turn', params, timeout=90)
                entry['status'] = 'accepted'
                self._save_actions()
                return self._action_result(entry)

    def _action_result(self, entry):
        return {'status': entry['status'], 'id': entry.get('id', entry['source']), 'host': self.host,
                'action': entry['action'], 'draft': entry['text'] if entry['status'] == 'created' and entry['action'] == 'edit-fork' else None,
                'source': {'id': entry['source'], 'title': entry['sourceTitle'], 'turnId': entry['turnId']}}

    def for_host(self, host):
        if host == self.host:
            return self
        available = self.hosts.hosts()
        if self.host != "local" or host not in available:
            raise KeyError("未知 SSH 主机")
        with self.lock:
            if host not in self.remote_bridges:
                folder = self.data_dir / 'hosts' / hashlib.sha256(host.encode()).hexdigest()[:16]
                self.remote_bridges[host] = Bridge(self.codex_home, folder, host, available[host]['alias'], ipc_path=self.ipc.path)
            return self.remote_bridges[host]

    def list(self, query="", limit=100, offset=0, archived=False):
        sources = [(self, "此电脑")]
        if self.host == 'local':
            sources.extend((self.for_host(host), info.get('displayName') or info['alias']) for host, info in self.hosts.hosts().items())
        def read(source):
            bridge, label = source
            try:
                rows = bridge.store.list(limit=limit + offset, archived=archived, query=query)
                rows = self.hosts.decorate(rows, bridge.host, label)
                with bridge.lock:
                    bridge.listed.update(row['id'] for row in rows)
                    for row in rows:
                        session = bridge.live.get(row['id'])
                        row['connected'] = bool(session and session.connected)
                        row['title'] = row.get('name') or row.get('title') or '未命名聊天'
                return rows, None
            except (RemoteUnavailable, StoreUnavailable) as exc:
                return [], {'host': bridge.host, 'label': label, 'error': str(exc)}
        with ThreadPoolExecutor(max_workers=min(4, len(sources))) as pool:
            results = list(pool.map(read, sources))
        self.host_errors = [error for _, error in results if error]
        rows = [row for group, _ in results for row in group]
        rows.sort(key=lambda row: (row['recency'], row['id'], row['host']), reverse=True)
        return rows[offset:offset + limit]

    def activity(self, identifiers):
        if not isinstance(identifiers, list) or len(identifiers) > 500 or any(not isinstance(v, str) for v in identifiers):
            raise ValueError('会话状态请求无效')
        with self.lock:
            sessions = []
            for identifier in dict.fromkeys(identifiers):
                if identifier not in self.listed:
                    continue
                session = self.live.get(identifier)
                if session is None:
                    session = self.live[identifier] = LiveSession(identifier)
                    session.activity_only = True
                session.touched = time.monotonic()
                sessions.append(session)
            pending = [s for s in sessions if not s.connected and time.monotonic() >= s.retry_at]
            if pending and not self.activity_following:
                self.activity_following = True
                def follow():
                    try:
                        for session in pending:
                            if self.closed.is_set(): break
                            session.retry_at = time.monotonic() + 15
                            self._attach(session, timeout=0)
                    finally:
                        with self.lock: self.activity_following = False
                threading.Thread(target=follow, daemon=True).start()
        rows = []
        for session in sessions:
            with session.condition:
                state = session.state or {}
                turns = ordered_turns(state)
                last = next((t for t in reversed(turns) if t.get('turnId')), {})
                rows.append({'id': session.id, 'host': self.host, 'connected': session.connected,
                             'status': state.get('threadRuntimeStatus', {}).get('type') if session.connected else 'unknown',
                             'turnId': last.get('turnId'), 'turnStatus': last.get('status')})
        return rows

    def upload(self, thread_id, identifier, name, data):
        self.store.get(thread_id)  # Uploads do not activate a desktop chat.
        return self.uploads.put(thread_id, identifier, name, data)

    def session(self, thread_id, attach=True, background=False, force=False):
        uuid.UUID(thread_id)
        with self.lock:
            session = self.live.get(thread_id)
        if session is None:
            # SSH/SQLite reads must not block IPC events for other chats.
            self.store.get(thread_id)
            with self.lock:
                session = self.live.setdefault(thread_id, LiveSession(thread_id))
        session.touched = time.monotonic()
        if background:
            if attach:
                self._refresh_async(session, force)
            return session
        if attach and not session.connected:
            self._attach(session)
        if session.state is None or session.activity_only:
            fallback = self.store.history(thread_id)
            with session.condition:
                if session.state is None or session.activity_only:
                    session.set_history(fallback)
                    session.activity_only = False
        return session

    def _refresh_async(self, session, force=False):
        with session.condition:
            if (session.connected and not session.activity_only) or session.connecting or self.closed.is_set():
                return
            if not force and time.monotonic() < session.retry_at and not session.activity_only:
                return
            session.connecting = True
            session.changed()
        def refresh():
            try:
                if session.state is None or session.activity_only:
                    try:
                        fallback = self.store.history(session.id)
                        with session.condition:
                            session.set_history(fallback)
                    except (OSError, ValueError, KeyError, RemoteUnavailable, StoreUnavailable):
                        # A missing saved rollout must not prevent a live snapshot.
                        logging.getLogger(__name__).warning("Saved history unavailable; trying desktop snapshot")
                    finally:
                        session.activity_only = False
                if not self.closed.is_set():
                    self._attach(session)
            except Exception:
                logging.getLogger(__name__).exception("Background session read failed")
                with session.condition:
                    session.error = "读取会话失败，请重新连接或查看网关日志。"
            finally:
                with session.condition:
                    session.connecting = False
                    session.retry_at = time.monotonic() + 15
                    session.changed()
        threading.Thread(target=refresh, daemon=True).start()

    def _attach(self, session, timeout=1.5):
        with session.attach_lock:
            if session.connected or self.closed.is_set():
                return
            try:
                # Owners answer a follow with a fresh snapshot. This also works
                # for remote/background owners absent from owner-discovery.
                self.ipc.connect()
                with session.condition:
                    session.owner = None
                    session.revision = None
                    session.discovering = True
                self.ipc.follow(session.id, None, host=self.host)
                with session.condition:
                    session.condition.wait_for(lambda: session.connected or self.closed.is_set(), timeout=timeout)
                    # Keep following: a cold chat can publish after this short
                    # wait. Missing live state does not make saved history fail.
            except IPCError as exc:
                with session.condition:
                    session.discovering = False
                    session.connected = False
                    session.error = str(exc)
                    session.changed()

    def activate(self, thread_id):
        """Explicit phone operation only; reads and notification watches never navigate."""
        session = self.session(thread_id, background=True)
        with session.activation_lock:
            if session.connected:
                return session
            with session.condition:
                session.activating = True
                session.changed()
            try:
                # Reuse an in-flight passive attach before considering navigation.
                self._attach(session)
                if session.connected:
                    return session
                if self.closed.is_set():
                    raise IPCError('网关正在停止；操作未发送，请稍后重试。')
                if session.last_activation is not None and time.monotonic() - session.last_activation < 20:
                    raise IPCError(session.error or '桌面仍在加载此聊天；操作未发送，请稍后重试。')
                session.last_activation = time.monotonic()
                try:
                    open_in_desktop(session.id, self.host)
                except (OSError, CreationError, subprocess.SubprocessError) as exc:
                    raise IPCError('无法在电脑 Codex 中加载此聊天；操作未发送，请检查 Codex 是否已安装并运行。') from exc
                deadline = time.monotonic() + 20
                while not session.connected and not self.closed.is_set() and time.monotonic() < deadline:
                    self._attach(session, timeout=.75)
                    if not session.connected:
                        self.closed.wait(.25)
                if not session.connected:
                    raise IPCError('桌面仍在加载此聊天；操作未发送，请稍后重试。')
                return session
            except IPCError as exc:
                with session.condition:
                    session.error = str(exc)
                raise
            finally:
                with session.condition:
                    session.activating = False
                    session.changed()

    def _event(self, message):
        if message.get("method") == "client-status-changed":
            params = message.get("params", {})
            if params.get("status") == "disconnected":
                with self.lock:
                    sessions = list(self.live.values())
                for session in sessions:
                    if session.owner == params.get("clientId"):
                        self._invalidate(session, "桌面会话连接已断开，正在等待重连")
            return
        if message.get("method") != "thread-stream-state-changed":
            return
        if message.get("version") != 11:
            self._disconnected()
            return
        params = message.get("params", {})
        if params.get("hostId") != self.host:
            return
        with self.lock:
            session = self.live.get(params.get("conversationId"))
        if session is None:
            return
        change = params.get("change", {})
        with session.condition:
            if session.discovering and change.get("type") == "snapshot" and (change.get("conversationState") or {}).get("id") == session.id:
                session.owner = message.get("sourceClientId")
                session.discovering = False
            if not session.owner or message.get("sourceClientId") != session.owner:
                return
            try:
                if change.get("type") == "snapshot":
                    state = change["conversationState"]
                    if state.get("id") != session.id:
                        raise ValueError("Session mismatch")
                    session.state = state
                elif change.get("type") == "patches":
                    if session.revision is None or change.get("baseRevision") != session.revision:
                        raise ValueError("Revision mismatch")
                    session.state = apply_patches(session.state, change["patches"])
                else:
                    return
                session.revision = change["revision"]
                session.connected = True
                session.error = None
                session.changed()
            except (ValueError, KeyError, IndexError, TypeError):
                session.connected = False
                session.revision = None
                session.error = "同步状态发生变化，正在重新读取桌面快照"
                session.changed()

    def _invalidate(self, session, message):
        with session.condition:
            session.connected = False
            session.revision = None
            session.error = message
            session.changed()

    def _disconnected(self):
        with self.lock:
            sessions = list(self.live.values())
        for session in sessions:
            self._invalidate(session, "桌面 App 连接已断开，正在等待重连")

    def _maintain(self):
        delay = 3
        while not self.closed.wait(delay):
            with self.lock:
                sessions = list(self.live.values())
            for session in sessions:
                with self.submit_lock:
                    queued = [(k, dict(v)) for k, v in self.submissions.items() if k.startswith(session.id + ":") and v["status"] == "queued"]
                if queued and session.connected and session.view().get("status") == "idle":
                    key, entry = queued[0]
                    try:
                        self._send_queued(session, key, entry)
                    except Exception:
                        pass  # Unknown outcomes stay recorded and are never automatically replayed.
                if (session.viewers > 0 or queued or session.watched) and not session.connected:
                    self._refresh_async(session)
                elif session.viewers == 0 and not queued and not session.watched and time.monotonic() - session.touched > 300:
                    with self.lock:
                        if session.viewers != 0 or session.watched:
                            continue
                        self.live.pop(session.id, None)
                    if session.connected or session.discovering:
                        try:
                            self.ipc.follow(session.id, session.owner, False, host=self.host)
                        except IPCError:
                            pass
            delay = 3 if self.ipc.client_id else min(30, delay * 2)

    def _target(self, thread_id):
        session = self.activate(thread_id)
        with session.condition:
            if not session.connected or not session.owner:
                raise IPCError(session.error or "请先在桌面 App 打开此聊天")
        return session

    def _call(self, session, method, params, timeout=30):
        return self.ipc.request(method, {"conversationId": session.id, **params}, target=session.owner, host=self.host, timeout=timeout)["result"]

    def _save_ledger(self):
        # Keep accepted or uncertain submissions durable across process restarts.
        target = self.ledger_path.with_suffix(".tmp")
        target.write_text(json.dumps(self.submissions, ensure_ascii=False), encoding='utf-8')
        target.chmod(0o600)
        target.replace(self.ledger_path)

    def view(self, thread_id, attach=True, background=False, force=False):
        session = self.session(thread_id, attach=attach, background=background, force=force)
        view = session.view()
        view["host"] = self.host
        view["hostLabel"] = "此电脑" if self.host == "local" else self.hosts.hosts().get(self.host, {}).get("displayName", self.host)
        with session.condition:
            artifacts = artifact_paths(session.state or {}, self.store.home) if self.host == "local" else {}
        view["files"] = [{"id": k, "name": v["name"], "reference": v["reference"], "image": v["image"]} for k, v in artifacts.items()]
        self._submission_meta(session, view)
        view["forkedFrom"] = self._fork_origin(thread_id)
        return view

    @staticmethod
    def _goal_text(objective):
        return ("请开启本会话的原生目标模式：先调用 create_goal，将下方原文设为 objective，"
                "再持续推进该目标。不要只用文字声称已开启；若工具不可用，请明确说明。\n\n" + objective)

    def _submission_meta(self, session, view):
        with self.submit_lock:
            entries = [(k.split(":")[1], copy.deepcopy(v)) for k, v in self.submissions.items()
                       if k.startswith(session.id + ":")]
        view["submissions"] = [{"id": k, "text": v["text"], "status": v["status"], **({'attachments': v['attachmentNames']} if v.get('attachmentNames') else {})}
                               for k, v in entries if v["status"] in ("queued", "unknown")]
        goals = [(k, v) for k, v in entries if v.get("workMode") == "goal"]
        view["goalSubmission"] = None
        if not goals:
            return
        key, entry = max(goals, key=lambda pair: pair[1]["at"])
        if entry.get("goalConfirmed"):
            return
        goal = view.get("goal")
        if goal and goal != entry.get("previousGoal") and goal.get("objective", "").strip() == entry["text"].strip():
            with self.submit_lock:
                self.submissions[session.id + ":" + key]["goalConfirmed"] = True
                self._save_ledger()
            return
        status = "unknown" if entry["status"] == "unknown" else "pending"
        with session.condition:
            for turn in ordered_turns(session.state or {}):
                params = turn.get("params", {})
                if (params.get("clientUserMessageId") == key or
                        any(item.get("text") == self._goal_text(entry["text"]) for item in params.get("input", []))):
                    if turn.get("status") in ("completed", "failed", "interrupted"):
                        status = "unconfirmed"
        view["goalSubmission"] = {"id": key, "objective": entry["text"], "status": status}

    def artifact(self, thread_id, artifact_id):
        session = self.session(thread_id, attach=False)
        with session.condition:
            files = artifact_paths(session.state or {}, self.store.home) if self.host == "local" else {}
        if artifact_id not in files:
            raise KeyError("文件不属于此聊天的工作目录")
        return files[artifact_id]

    def timeline_read(self, thread_id, mode='page', **options):
        session = self.session(thread_id, background=True)
        with session.condition:
            projection = session.timeline
            if projection.sequence != session.sequence:
                projection.update(session.view())
            if mode == 'detail':
                result = projection.detail(**options)
                texts = [projection.details[result['key']]]
            else:
                result = projection.changes(**options) if mode == 'changes' else projection.page(**options)
                result['meta'] = dict(result['meta'], host=self.host,
                                      hostLabel='此电脑' if self.host == 'local' else self.hosts.hosts().get(self.host, {}).get('displayName', self.host))
                texts = [row['text'] for row in result['rows'] if row['role'] == 'assistant']
            cwd = (session.state or {}).get('cwd')
        # Resolve only links in the delivered page, not every file in the chat.
        file_state = {'cwd': cwd, 'turns': [{'items': [{'type': 'agentMessage', 'text': text} for text in texts]}]}
        artifacts = artifact_paths(file_state, self.store.home) if self.host == 'local' else {}
        result['files'] = [{'id': k, 'name': v['name'], 'reference': v['reference'], 'image': v['image']} for k, v in artifacts.items()]
        if mode != 'detail':
            self._submission_meta(session, result['meta'])
            result['meta']['forkedFrom'] = self._fork_origin(thread_id)
        return result

    def catalog(self, thread_id, refresh=False):
        session = self.session(thread_id)
        with session.condition:
            cwd = session.state.get("cwd")
            model = session.state.get("latestModel")
            effort = session.state.get("latestReasoningEffort") or (session.state.get("latestThreadSettings") or {}).get("effort")
        catalog = self.catalog_reader.get(cwd, refresh=refresh)
        with session.condition:
            view = session.view()
        return {**catalog, "currentModel": model, "currentEffort": effort,
                'fastMode': {**catalog.get('fastMode', {}), 'allowed': view.get('provider') == 'openai' and catalog.get('fastMode', {}).get('allowed') is True},
                **({'currentServiceTier': view['serviceTier']} if 'serviceTier' in view else {})}

    def _resolve_skills(self, session, skills):
        if not isinstance(skills, list) or len(skills) > 8 or not all(isinstance(s, str) for s in skills):
            raise ValueError("最多选择 8 个 Skill")
        if not skills:
            return []
        catalog = self.catalog(session.id)
        by_id = {s["id"]: s for s in catalog["skills"]}
        selected = []
        for key in sorted(set(skills)):
            skill = by_id.get(key)
            if not skill or (self.host == "local" and not Path(skill["path"]).is_file()):
                raise ValueError("Skill 不可用，请刷新列表")
            selected.append({"id": key, "name": skill["name"], "path": skill["path"]})
        return selected

    def settings(self, thread_id, model, effort, *, fast_mode=None):
        if not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./:@+-]{0,199}", model):
            raise ValueError("模型 ID 格式不正确")
        if effort not in ("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"):
            raise ValueError("请选择有效的推理强度")
        if fast_mode is not None and not isinstance(fast_mode, bool):
            raise ValueError('Fast 模式开关无效')
        session = self._target(thread_id)
        with session.action_lock:
            # Recheck login/model/policy on the execution host before a speed change.
            catalog = self.catalog(thread_id, refresh=fast_mode is not None)
            known = next((m for m in catalog["models"] if m["id"] == model), None)
            if known and effort not in known["efforts"]:
                raise ValueError("这个模型不支持所选推理强度")
            settings = {'model': model, 'effort': effort}
            if fast_mode is not None:
                if not catalog.get('fastMode', {}).get('allowed') or not known or not known.get('fastTier'):
                    raise ValueError('当前账号、模型或工作区不支持 Fast 模式，请刷新模型设置')
                # null can inherit a default; "default" explicitly opts out of Fast.
                settings['serviceTier'] = known['fastTier'] if fast_mode else 'default'
            result = self._call(session, "thread-follower-update-thread-settings", {"threadSettings": settings})
        if not result.get("applied"):
            raise IPCError("桌面未应用模型设置，请刷新后重试")
        with session.condition:
            confirmed = session.condition.wait_for(lambda: session.state.get("latestModel") == model and session.view().get("effort") == effort and
                (fast_mode is None or (session.state.get('latestThreadSettings') or {}).get('serviceTier') == settings['serviceTier']), timeout=5)
        return {"applied": True, "confirmed": confirmed, "model": model, "effort": effort,
                **({'serviceTier': settings['serviceTier']} if fast_mode is not None else {})}

    def cancel_queued(self, thread_id, submission_id):
        uuid.UUID(submission_id)
        session = self.session(thread_id, attach=False)
        with session.action_lock:
            with self.submit_lock:
                key = thread_id + ":" + submission_id
                prior = self.submissions.get(key)
                if not prior or prior["status"] != "queued":
                    raise ValueError("此消息已离开队列，请查看会话")
                prior["status"] = "cancelled"
                self._save_ledger()
            with session.condition:
                session.changed()
        return {"status": "cancelled"}

    def send(self, thread_id, text, submission_id, mode="send", skills=None, *, work_mode=None, plan_response=None, attachments=None):
        uuid.UUID(submission_id)
        identifiers = [] if attachments is None else attachments
        files = self.uploads.resolve(thread_id, identifiers)
        if not isinstance(text, str) or (not text.strip() and not files) or len(text) > 100000:
            raise ValueError("请输入 1–100000 字的消息")
        if mode not in ("send", "steer", "queue"):
            raise ValueError("未知发送方式")
        if work_mode not in (None, "default", "plan", "goal"):
            raise ValueError("未知工作模式")
        if mode == "steer" and work_mode is not None:
            raise ValueError("补充当前任务时不能切换工作模式")
        if work_mode == "goal" and (mode != "send" or not text.strip() or len(text) > 4000):
            raise ValueError("目标需在当前任务结束后直接发送，且不超过 4000 字")
        session = self._target(thread_id)
        selected = self._resolve_skills(session, [] if skills is None else skills)
        key = thread_id + ":" + submission_id
        with session.action_lock:
            with self.submit_lock:
                prior = self.submissions.get(key)
                if prior:
                    if (prior["text"] != text or prior["mode"] != mode or prior.get("skills", []) != selected or
                            prior.get("workMode") != work_mode or prior.get("planResponse") != plan_response or prior.get('attachments', []) != identifiers):
                        raise ValueError("同一消息标识不能用于不同内容")
                    return {"status": prior["status"], "duplicate": True, "id": submission_id}
            with session.condition:
                active = session.state.get("threadRuntimeStatus", {}).get("type") == "active"
                if active and mode == "send":
                    raise ValueError("Codex 正在执行。请选择「排队发送」或「补充当前任务」。")
                if not active and mode == "steer":
                    raise ValueError("当前任务已经结束，请使用普通发送")
                if work_mode == "goal":
                    goal = session.state.get("threadGoal")
                    if goal and goal.get("status") != "complete":
                        raise ValueError("此聊天已有未完成目标，请先处理当前目标")
                if work_mode is not None and not (session.state.get("latestModel") or
                        (session.state.get("latestCollaborationMode") or {}).get("settings", {}).get("model")):
                    raise ValueError("尚未取得桌面模型设置，请重新连接后再切换模式")
                if plan_response is not None:
                    pending = next((r for r in session.state.get("requests", [])
                                    if r.get("id") == plan_response["requestId"] and r.get("method") == "item/plan/requestImplementation"), None)
                    if pending is None:
                        raise ValueError("此计划已处理或已过期，请刷新后查看最新计划")
                    if plan_response["action"] == "implement":
                        if text != "Implement the following plan:\n\n" + pending.get("params", {}).get("planContent", ""):
                            raise ValueError("计划已更新，请刷新后重试")
            if work_mode == "goal":
                pending_view = session.view()
                self._submission_meta(session, pending_view)
                if (pending_view.get("goalSubmission") or {}).get("status") in ("pending", "unknown"):
                    raise ValueError("上一条目标请求尚未确认，请先查看会话结果")
            with self.submit_lock:
                entry = {"text": text, "mode": mode, "skills": selected, "status": "queued" if mode == "queue" else "unknown", "at": time.time()}
                if identifiers:
                    entry['attachments'] = identifiers
                    entry['attachmentNames'] = [f['name'] for f in files]
                if work_mode is not None:
                    entry["workMode"] = work_mode
                if work_mode == "goal":
                    entry["previousGoal"] = pending_view.get("goal")
                if plan_response is not None:
                    entry["planResponse"] = plan_response
                self.submissions[key] = entry
                self._save_ledger()
            with session.condition:
                session.changed()
            if mode == "queue":
                return {"status": "queued", "id": submission_id}
            return self._dispatch(session, key, entry)

    def _send_queued(self, session, key, entry):
        with session.action_lock:
            with session.condition:
                if not session.connected or session.state.get("threadRuntimeStatus", {}).get("type") != "idle":
                    return
            with self.submit_lock:
                if self.submissions[key]["status"] != "queued":
                    return
                self.submissions[key]["status"] = "unknown"
                self._save_ledger()
            try:
                self._dispatch(session, key, entry)
            finally:
                with session.condition:
                    session.changed()

    def _dispatch(self, session, key, entry):
        submission_id = key.split(":")[1]
        text = self._goal_text(entry["text"]) if entry.get("workMode") == "goal" else entry["text"]
        files = self.uploads.resolve(session.id, entry.get('attachments', []))
        if files:
            manifest = '# Attached files\n' + '\n'.join(json.dumps({'name': f['name'], 'path': f['path']}, ensure_ascii=False) for f in files)
            text = manifest + '\n\n' + text if entry.get('workMode') == 'goal' else text + '\n\n' + manifest
        request = {"threadId": session.id, "input": [{"type": "text", "text": text, "text_elements": []}], "clientUserMessageId": submission_id}
        request['input'].extend({'type': 'localImage', 'path': f['path']} for f in files if f['image'])
        context = {'attachments': [], 'commentAttachments': []}
        if files:
            context['fileAttachments'] = [{'path': f['path'], 'label': f['name']} for f in files]
        if entry.get("workMode") is not None:
            with session.condition:
                current = session.state
                model = current.get("latestModel") or (current.get("latestCollaborationMode") or {}).get("settings", {}).get("model")
                effort = current.get("latestReasoningEffort") or (current.get("latestThreadSettings") or {}).get("effort")
            if not model:
                raise ValueError("尚未取得桌面模型设置，请重新连接后再切换模式")
            request["collaborationMode"] = {"mode": "plan" if entry["workMode"] == "plan" else "default",
                                            "settings": {"model": model, "reasoning_effort": effort, "developer_instructions": None}}
        request["input"].extend({"type": "skill", "name": s["name"], "path": s["path"]} for s in entry.get("skills", []))
        if entry["mode"] != "steer":
            response = self._call(session, "thread-follower-start-turn", {
                "turnStart": {"request": request, "context": {"inheritThreadSettings": True, **context}}}, timeout=90)
        else:
            response = self._call(session, "thread-follower-steer-turn", {
                "input": request["input"], "clientUserMessageId": submission_id,
                "restoreMessage": {"request": request, "context": context},
                "attachments": []}, timeout=30)
        with self.submit_lock:
            self.submissions[key]["status"] = "accepted"
            self._save_ledger()
        with session.condition:
            session.changed()
        return {"status": "accepted", "id": submission_id, "result": response}

    def interrupt(self, thread_id):
        session = self._target(thread_id)
        with session.condition:
            active = next((t for t in reversed(ordered_turns(session.state)) if t.get("status") == "inProgress"), None)
            if not active or not active.get("turnId"):
                raise ValueError("当前没有可停止的任务")
            turn_id = active["turnId"]
        return self._call(session, "thread-follower-interrupt-turn", {"mode": "user-stop", "expectedTurnId": turn_id})

    def full_history(self, thread_id):
        session = self._target(thread_id)
        return self._call(session, "thread-follower-load-complete-history", {}, timeout=180)

    def respond(self, thread_id, request_id, response):
        session = self._target(thread_id)
        plan_id = str(uuid.uuid5(uuid.UUID(thread_id), "plan:" + str(request_id)))
        with self.submit_lock:
            prior = copy.deepcopy(self.submissions.get(thread_id + ":" + plan_id))
        if prior and prior.get("planResponse"):
            if prior["planResponse"] != {"requestId": request_id, **response}:
                raise ValueError("此计划已经提交了不同操作，请查看会话结果")
            return {"status": prior["status"], "duplicate": True, "id": plan_id}
        with session.condition:
            async_pending = next((r for r in async_requests(session.state) if r["id"] == request_id), None)
        if async_pending:
            questions = async_pending["params"]["questions"]
            answers = response.get("answers", {})
            if set(answers) != {q["id"] for q in questions} or any(not isinstance(v, list) or len(v) != 1 or not isinstance(v[0], str) or not v[0].strip() or len(v[0]) > 20000 for v in answers.values()):
                raise ValueError("请回答所有问题")
            replies = [{"questionItemId": q["id"], "question": q["question"], "answer": answers[q["id"]][0]} for q in questions]
            text = "<send_user_message_question_reply>\n" + json.dumps(replies, ensure_ascii=False, separators=(",", ":")) + "\n</send_user_message_question_reply>"
            submission = str(uuid.uuid5(uuid.UUID(thread_id), request_id + text))
            with session.condition:
                active = session.state.get("threadRuntimeStatus", {}).get("type") == "active"
            return self.send(thread_id, text, submission, "steer" if active else "send")
        with session.condition:
            pending = next((r for r in session.state.get("requests", []) if str(r.get("id")) == str(request_id)), None)
            if not pending:
                raise ValueError("此请求已处理或已过期")
            pending = copy.deepcopy(pending)
        if not normalize_request(pending)["supported"]:
            raise ValueError("此类请求请在桌面 App 中处理")
        method = pending.get("method", "")
        params = pending.get("params", {})
        if method == "item/plan/requestImplementation":
            action = response.get("action")
            if action == "implement" and set(response) == {"action"}:
                plan = params.get("planContent")
                if not isinstance(plan, str) or not plan.strip():
                    raise ValueError("计划内容尚未加载，请刷新后重试")
                text, work_mode = "Implement the following plan:\n\n" + plan, "default"
            elif action == "revise" and set(response) == {"action", "text"} and isinstance(response.get("text"), str) and response["text"].strip():
                text, work_mode = response["text"], "plan"
            else:
                raise ValueError("请选择执行计划或填写修改意见")
            return self.send(thread_id, text, plan_id, work_mode=work_mode,
                             plan_response={"requestId": request_id, **response})
        mapping = {
            "item/commandExecution/requestApproval": "thread-follower-command-approval-decision",
            "item/fileChange/requestApproval": "thread-follower-file-approval-decision",
            "item/permissions/requestApproval": "thread-follower-permissions-request-approval-response",
            "item/tool/requestUserInput": "thread-follower-submit-user-input",
            "tool/requestUserInput": "thread-follower-submit-user-input",
            "mcpServer/elicitation/request": "thread-follower-submit-mcp-server-elicitation-response",
        }
        if method not in mapping:
            raise ValueError("此类请求请在桌面 App 中处理")
        payload = {"requestId": pending["id"]}
        if method in ("item/commandExecution/requestApproval", "item/fileChange/requestApproval"):
            decision = response.get("decision")
            if decision not in ("accept", "decline", "cancel"):
                raise ValueError("只支持本次批准、拒绝或取消")
            available = params.get("availableDecisions")
            if available and decision not in available:
                raise ValueError("该审批不支持所选操作")
            payload["decision"] = decision
        elif method == "item/permissions/requestApproval":
            if response.get("decision") not in ("accept", "decline"):
                raise ValueError("请选择批准或拒绝")
            payload["response"] = {"permissions": params.get("permissions", {}) if response["decision"] == "accept" else {}, "scope": "turn"}
        elif method in ("item/tool/requestUserInput", "tool/requestUserInput"):
            answers = response.get("answers")
            if not isinstance(answers, dict):
                raise ValueError("请填写问题答案")
            allowed_ids = {q["id"] for q in params.get("questions", [])}
            if not allowed_ids or set(answers) != allowed_ids or any(not isinstance(v, list) or not v or not all(isinstance(s, str) and len(s) <= 20000 for s in v) for v in answers.values()):
                raise ValueError("答案不完整或格式不正确")
            payload["response"] = {"answers": {k: {"answers": v} for k, v in answers.items()}}
        else:
            action = response.get("action")
            if action not in ("accept", "decline", "cancel"):
                raise ValueError("未知确认操作")
            content = response.get("content")
            if action == "accept":
                validate_form(content, params.get("requestedSchema", {}))
            payload["response"] = {"action": action, "content": content if action == "accept" else None}
        return self._call(session, mapping[method], payload)

    def close(self):
        self.closed.set()
        for bridge in list(self.remote_bridges.values()):
            bridge.close()
        with self.lock:
            sessions = list(self.live.values())
        for session in sessions:
            try:
                if session.connected or session.discovering:
                    self.ipc.follow(session.id, session.owner, False, host=self.host)
            except IPCError:
                pass
        self.ipc.close()


def validate_form(value, schema):
    """Validate the small JSON-schema form subset that the phone can render."""
    from .model import form_supported
    if not form_supported(schema):
        raise ValueError("此表单需要在桌面填写")
    if not isinstance(value, dict):
        raise ValueError("请填写表单")
    properties = schema.get("properties", {})
    if set(value) - set(properties) or set(schema.get("required", [])) - set(value):
        raise ValueError("表单字段不完整")
    for key, item in value.items():
        field = properties[key]
        kind = field.get("type", "string")
        valid = ((kind == "string" and isinstance(item, str)) or
                 (kind == "boolean" and isinstance(item, bool)) or
                 (kind in ("number", "integer") and type(item) in (int, float) and (kind != "integer" or int(item) == item)))
        if not valid or ("enum" in field and item not in field["enum"]):
            raise ValueError("表单字段格式不正确：" + key)
        if isinstance(item, str) and not field.get("minLength", 0) <= len(item) <= field.get("maxLength", 20000):
            raise ValueError("表单文本长度不正确：" + key)
        if type(item) in (int, float) and not field.get("minimum", float("-inf")) <= item <= field.get("maximum", float("inf")):
            raise ValueError("表单数值超出范围：" + key)
