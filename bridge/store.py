"""Read-only discovery and history for existing desktop chats."""
import json
import sqlite3
from contextlib import closing
from pathlib import Path


class SessionStore:
    def __init__(self, codex_home):
        self.home = Path(codex_home).resolve()

    def _connect(self):
        databases = sorted(self.home.glob("state_*.sqlite"), key=lambda p: p.stat().st_mtime, reverse=True)
        if not databases:
            raise RuntimeError("找不到 Codex 会话数据库")
        conn = sqlite3.connect(databases[0].as_uri() + "?mode=ro", uri=True, timeout=3)
        conn.row_factory = sqlite3.Row
        return conn

    def list(self, query="", limit=100, offset=0, archived=False):
        with closing(self._connect()) as conn:
            columns = {r[1] for r in conn.execute("PRAGMA table_info(threads)")}
            fields = [name for name in ("id", "name", "title", "cwd", "updated_at", "updated_at_ms", "recency_at", "recency_at_ms", "model_provider", "model", "originator", "source", "archived", "is_pinned") if name in columns]
            where = ["archived = ?"]
            params = [int(archived)]
            if "originator" in columns:
                # Older desktop imports have no originator but retain their app source.
                where.append("(originator IN ('Codex Desktop', 'codex_work_desktop') OR (originator IS NULL AND source = 'vscode'))")
            if "thread_source" in columns:
                where.append("COALESCE(thread_source, '') != 'subagent'")
            if "source" in columns:
                where.append("COALESCE(source, '') NOT LIKE '%\"subagent\"%'")
            if query:
                search_fields = [name for name in ("name", "title", "cwd") if name in columns]
                where.append("(" + " OR ".join(name + " LIKE ?" for name in search_fields) + ")")
                params += ["%" + query + "%"] * len(search_fields)
            recency = "COALESCE(recency_at_ms, recency_at * 1000, updated_at * 1000)" if "recency_at_ms" in columns else "COALESCE(recency_at, updated_at)" if "recency_at" in columns else "updated_at"
            rows = conn.execute("SELECT " + ",".join(fields) + " FROM threads WHERE " + " AND ".join(where) + " ORDER BY " + recency + " DESC, id DESC LIMIT ? OFFSET ?", params + [limit, offset]).fetchall()
            return [dict(row) for row in rows]

    def get(self, thread_id):
        with closing(self._connect()) as conn:
            row = conn.execute("SELECT * FROM threads WHERE id = ?", (thread_id,)).fetchone()
            if not row:
                raise KeyError("找不到这个桌面会话")
            result = dict(row)
            desktop = result.get("originator") in ("Codex Desktop", "codex_work_desktop") or (result.get("originator") is None and result.get("source") == "vscode")
            if not desktop or result.get("thread_source") == "subagent" or '"subagent"' in (result.get("source") or ""):
                raise KeyError("不是桌面 App 会话")
            return result

    def history(self, thread_id):
        meta = self.get(thread_id)
        path = Path(meta["rollout_path"])
        # The file path must be an actual rollout in this user's Codex home.
        resolved = path.resolve()
        if not any(root.resolve() in resolved.parents for root in (self.home / "sessions", self.home / "archived_sessions")):
            raise ValueError("会话记录路径不在 Codex 数据目录中")
        items, turns, current = [], [], None
        with resolved.open(encoding='utf-8') as stream:
            for line in stream:
                try:
                    record = json.loads(line)
                except ValueError:
                    continue
                payload = record.get("payload", {})
                if record.get("type") == "event_msg" and payload.get("type") == "task_started":
                    current = {"turnId": payload.get("turn_id"), "status": "inProgress", "items": []}
                    turns.append(current)
                    items = current["items"]
                elif record.get("type") == "event_msg" and payload.get("type") in ("task_complete", "turn_aborted"):
                    if current:
                        current["status"] = "completed" if payload["type"] == "task_complete" else "interrupted"
                elif record.get("type") == "response_item":
                    if current is None:
                        current = {"turnId": "history", "status": "completed", "items": []}
                        turns.append(current)
                        items = current["items"]
                    if payload.get("type") == "message" and payload.get("role") in ("user", "assistant"):
                        role = payload["role"]
                        if role == "user":
                            items.append({"id": str(len(items)), "type": "userMessage", "content": payload.get("content", [])})
                        else:
                            items.append({"id": str(len(items)), "type": "agentMessage", "text": "\n".join(x.get("text", "") for x in payload.get("content", []) if isinstance(x, dict)), "phase": payload.get("phase")})
                    elif payload.get("type") in ("agent_message", "agentMessage"):
                        items.append({"id": payload.get("id", str(len(items))), "type": "agentMessage", "text": payload.get("text", "")})
                    elif payload.get("type") in ("function_call", "custom_tool_call", "function_call_output", "custom_tool_call_output"):
                        items.append({"id": payload.get("call_id", str(len(items))), "type": "storedToolEvent", **payload})
        return {"id": thread_id, "title": meta.get("name") or meta.get("title"), "cwd": meta["cwd"],
                "latestModel": meta.get("model"), "modelProvider": meta.get("model_provider"),
                "turns": turns, "requests": [], "threadRuntimeStatus": {"type": "notLoaded"}}
