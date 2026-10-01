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
from .store import SessionStore
from .files import artifact_paths
from .catalog import Catalog
from .remote import AppHosts, RemoteStore, RemoteCatalog, RemoteUnavailable, ssh_read, payload
from .create import create_empty, open_in_desktop, CreationError
from .timeline import Timeline


class LiveSession:
    def __init__(self, thread_id):
        self.id = thread_id
        self.owner = None
        self.discovering = False
        self.state = None
        self.revision = None
        self.sequence = 0
        self.connected = False
        self.connecting = False
        self.retry_at = 0
        self.error = None
        self.viewers = 0
        self.watched = False
        self.touched = time.monotonic()
        self.condition = threading.Condition(threading.RLock())
        self.attach_lock = threading.Lock()
        self.action_lock = threading.Lock()
        self.timeline = Timeline()

    def changed(self):
        self.sequence += 1
        self.condition.notify_all()

    def view(self):
        with self.condition:
            result = normalize_state(self.state or {"id": self.id}, self.connected)
            result["sequence"] = self.sequence
            result["connectionError"] = self.error
            result["connecting"] = self.connecting
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
        self.store = RemoteStore(alias) if alias else SessionStore(codex_home)
        self.catalog_reader = RemoteCatalog(alias) if alias else Catalog(codex_home, codex_bin)
        self.live = {}
        self.lock = threading.RLock()
        self.ipc = DesktopIPC(ipc_path or ipc_endpoint(codex_home), self._event, self._disconnected)
        self.closed = threading.Event()
        Path(data_dir).mkdir(parents=True, exist_ok=True, mode=0o700)
        self.ledger_path = Path(data_dir) / "submissions.json"
        self.submit_lock = threading.Lock()
        self.create_lock = threading.Lock()
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
                    for row in rows:
                        session = bridge.live.get(row['id'])
                        row['connected'] = bool(session and session.connected)
                        row['title'] = row.get('name') or row.get('title') or '未命名聊天'
                return rows, None
            except RemoteUnavailable as exc:
                return [], {'host': bridge.host, 'label': label, 'error': str(exc)}
        with ThreadPoolExecutor(max_workers=min(4, len(sources))) as pool:
            results = list(pool.map(read, sources))
        self.host_errors = [error for _, error in results if error]
        rows = [row for group, _ in results for row in group]
        rows.sort(key=lambda row: (row['recency'], row['id'], row['host']), reverse=True)
        return rows[offset:offset + limit]

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
        if session.state is None:
            fallback = self.store.history(thread_id)
            with session.condition:
                if session.state is None:
                    session.state = fallback
                    session.changed()
        return session

    def _refresh_async(self, session, force=False):
        with session.condition:
            if session.connected or session.connecting or self.closed.is_set():
                return
            if not force and time.monotonic() < session.retry_at:
                return
            session.connecting = True
            session.changed()
        def refresh():
            try:
                if session.state is None:
                    try:
                        fallback = self.store.history(session.id)
                        with session.condition:
                            if session.state is None:
                                session.state = fallback
                                session.changed()
                    except (OSError, ValueError, KeyError, RemoteUnavailable):
                        # A missing saved rollout must not prevent a live snapshot.
                        logging.getLogger(__name__).warning("Saved history unavailable; trying desktop snapshot")
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

    def _attach(self, session):
        with session.attach_lock:
            if session.connected:
                return
            try:
                if self.host == "local":
                    owner = self.ipc.owner(session.id, self.host)
                    with session.condition:
                        session.owner = owner
                        session.revision = None
                    self.ipc.follow(session.id, owner, host=self.host)
                else:
                    # Remote owners publish snapshots but are not registered by local discovery.
                    self.ipc.connect()
                    with session.condition:
                        session.owner = None
                        session.revision = None
                        session.discovering = True
                    self.ipc.follow(session.id, None, host=self.host)
                with session.condition:
                    if not session.condition.wait_for(lambda: session.connected or self.closed.is_set(), timeout=8):
                        raise IPCError("尚未收到桌面实时快照。请在电脑 App 打开此聊天后重新连接。")
            except IPCError as exc:
                if self.host != "local":
                    try:
                        self.ipc.follow(session.id, None, False, host=self.host)
                    except IPCError:
                        pass
                with session.condition:
                    session.discovering = False
                    session.connected = False
                    session.error = "请先在电脑 Codex App 中打开这条聊天，再点击重新连接。" if "no-client-found" in str(exc) else str(exc)
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
                    if session.connected:
                        try:
                            self.ipc.follow(session.id, session.owner, False, host=self.host)
                        except IPCError:
                            pass
            delay = 3 if self.ipc.client_id else min(30, delay * 2)

    def _target(self, thread_id):
        session = self.session(thread_id)
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
        with self.submit_lock:
            view["submissions"] = [{"id": k.split(":")[1], "text": v["text"], "status": v["status"]}
                                   for k, v in self.submissions.items()
                                   if k.startswith(thread_id + ":") and v["status"] in ("queued", "unknown")]
        return view

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
            with self.submit_lock:
                result['meta']['submissions'] = [{'id': k.split(':')[1], 'text': v['text'], 'status': v['status']}
                                                 for k, v in self.submissions.items()
                                                 if k.startswith(thread_id + ':') and v['status'] in ('queued', 'unknown')]
        return result

    def catalog(self, thread_id, refresh=False):
        session = self.session(thread_id)
        with session.condition:
            cwd = session.state.get("cwd")
            model = session.state.get("latestModel")
            effort = session.state.get("latestReasoningEffort") or (session.state.get("latestThreadSettings") or {}).get("effort")
        catalog = self.catalog_reader.get(cwd, refresh=refresh)
        return {**catalog, "currentModel": model, "currentEffort": effort}

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

    def settings(self, thread_id, model, effort):
        if not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./:@+-]{0,199}", model):
            raise ValueError("模型 ID 格式不正确")
        if effort not in ("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"):
            raise ValueError("请选择有效的推理强度")
        session = self._target(thread_id)
        known = next((m for m in self.catalog(thread_id)["models"] if m["id"] == model), None)
        if known and effort not in known["efforts"]:
            raise ValueError("这个模型不支持所选推理强度")
        with session.action_lock:
            result = self._call(session, "thread-follower-update-thread-settings", {"threadSettings": {"model": model, "effort": effort}})
        if not result.get("applied"):
            raise IPCError("桌面未应用模型设置，请刷新后重试")
        with session.condition:
            confirmed = session.condition.wait_for(lambda: session.state.get("latestModel") == model and session.view().get("effort") == effort, timeout=5)
        return {"applied": True, "confirmed": confirmed, "model": model, "effort": effort}

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

    def send(self, thread_id, text, submission_id, mode="send", skills=None):
        uuid.UUID(submission_id)
        if not isinstance(text, str) or not text.strip() or len(text) > 100000:
            raise ValueError("请输入 1–100000 字的消息")
        if mode not in ("send", "steer", "queue"):
            raise ValueError("未知发送方式")
        session = self._target(thread_id)
        selected = self._resolve_skills(session, [] if skills is None else skills)
        key = thread_id + ":" + submission_id
        with session.action_lock:
            with self.submit_lock:
                prior = self.submissions.get(key)
                if prior:
                    if prior["text"] != text or prior["mode"] != mode or prior.get("skills", []) != selected:
                        raise ValueError("同一消息标识不能用于不同内容")
                    return {"status": prior["status"], "duplicate": True, "id": submission_id}
            with session.condition:
                active = session.state.get("threadRuntimeStatus", {}).get("type") == "active"
                if active and mode == "send":
                    raise ValueError("Codex 正在执行。请选择「排队发送」或「补充当前任务」。")
                if not active and mode == "steer":
                    raise ValueError("当前任务已经结束，请使用普通发送")
            with self.submit_lock:
                entry = {"text": text, "mode": mode, "skills": selected, "status": "queued" if mode == "queue" else "unknown", "at": time.time()}
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
        request = {"threadId": session.id, "input": [{"type": "text", "text": entry["text"], "text_elements": []}], "clientUserMessageId": submission_id}
        request["input"].extend({"type": "skill", "name": s["name"], "path": s["path"]} for s in entry.get("skills", []))
        if entry["mode"] != "steer":
            response = self._call(session, "thread-follower-start-turn", {
                "turnStart": {"request": request, "context": {"inheritThreadSettings": True, "attachments": [], "commentAttachments": []}}}, timeout=90)
        else:
            response = self._call(session, "thread-follower-steer-turn", {
                "input": request["input"], "clientUserMessageId": submission_id,
                "restoreMessage": {"request": request, "context": {"attachments": [], "commentAttachments": []}},
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
                if session.connected:
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
