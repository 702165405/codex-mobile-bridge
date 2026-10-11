"""Read-only model discovery for an OpenAI-compatible API."""
import json
import re
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener

from bridge.features.auth.tls import client_context


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the selected provider's credentials to another endpoint.
        return None


def model_catalog(base_url, key):
    request = Request(base_url.rstrip('/')+'/models', headers={
        **({'Authorization': 'Bearer '+key} if key else {}), 'Accept': 'application/json',
        'User-Agent': 'Codex-Mobile-Bridge'})
    opener = build_opener(NoRedirect(), HTTPSHandler(context=client_context()))
    try:
        with opener.open(request, timeout=12) as response:
            payload = response.read(1024*1024+1)
        if len(payload) > 1024*1024:
            raise ValueError('上游模型列表过大，请手动填写模型 ID')
        value = json.loads(payload)
        native = isinstance(value, dict) and 'data' not in value and 'models' in value
        rows = value.get('models' if native else 'data') if isinstance(value, dict) else (value if isinstance(value, list) else None)
        if not isinstance(rows, list):
            raise ValueError('上游未返回兼容的模型列表，请手动填写模型 ID')
        field = 'slug' if native else 'id'
        result = {}
        for row in rows:
            if not isinstance(row, dict) or (native and row.get('visibility') == 'hide'):
                continue
            identifier = row.get(field)
            if not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./:@+-]{0,199}', identifier):
                continue
            name = row.get('magpie_label') or row.get('display_name') or identifier
            if not isinstance(name, str) or not 0 < len(name) <= 200 or any(ord(c) < 32 or ord(c) == 127 for c in name):
                name = identifier
            levels = row.get('supported_reasoning_levels')
            efforts = list(dict.fromkeys(level.get('effort') for level in levels
                          if isinstance(level, dict) and level.get('effort') in
                          ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'))) if isinstance(levels, list) else []
            result[identifier] = {'id': identifier, 'name': name, 'efforts': efforts}
        if not result:
            raise ValueError('上游未返回可用模型，请手动填写模型 ID')
        return [result[identifier] for identifier in sorted(result)]
    except HTTPError as exc:
        exc.close()
        if exc.code in (401, 403):
            raise ValueError('上游拒绝访问，请检查 API Key 和模型列表权限') from None
        if 300 <= exc.code < 400:
            raise ValueError('上游要求跳转，请填写最终 API 地址后重试') from None
        raise ValueError('上游暂不提供模型列表，请检查 API 地址或手动填写模型 ID') from None
    except (URLError, OSError, TimeoutError, HTTPException):
        raise ValueError('无法连接上游，请检查 API 地址、网络和证书后重试') from None
    except (json.JSONDecodeError, UnicodeError):
        raise ValueError('上游未返回兼容的模型列表，请手动填写模型 ID') from None


def model_ids(base_url, key):
    return [model['id'] for model in model_catalog(base_url, key)]
