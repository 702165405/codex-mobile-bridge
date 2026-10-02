"""Private, immutable uploads scoped to a host and an existing chat."""
import hashlib
import json
import re
import threading
import uuid
from pathlib import Path

MAX_FILE = 20 * 1024 * 1024
MAX_FILES = 10
MAX_TOTAL = 100 * 1024 * 1024


def file_name(value):
    if not isinstance(value, str) or not value or len(value.encode('utf-8')) > 200 or value in ('.', '..') or re.search(r'[\\/\x00-\x1f\x7f]', value):
        raise ValueError('文件名无效')
    return value


def image_type(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n'): return 'image/png'
    if data.startswith(b'\xff\xd8\xff'): return 'image/jpeg'
    if data.startswith((b'GIF87a', b'GIF89a')): return 'image/gif'
    if data.startswith(b'RIFF') and data[8:12] == b'WEBP': return 'image/webp'
    return None


class Uploads:
    def __init__(self, directory, remote=None):
        self.root = Path(directory)/'uploads'
        self.remote = remote
        self.lock = threading.RLock()

    def put(self, thread, identifier, name, data):
        uuid.UUID(thread); uuid.UUID(identifier); file_name(name)
        if not isinstance(data, bytes) or not 0 < len(data) <= MAX_FILE:
            raise ValueError('每个附件需为 1 字节至 20 MB')
        digest = hashlib.sha256(data).hexdigest()
        with self.lock:
            folder = self.root/thread/identifier
            meta = folder.with_suffix('.json')
            if meta.exists():
                row = self.get(thread, identifier)
                if row['sha256'] != digest or row['name'] != name:
                    raise ValueError('同一附件标识不能用于不同文件')
                return self.public(row)
            folder.mkdir(parents=True, exist_ok=True, mode=0o700)
            path = folder/name
            if path.is_symlink() or folder.resolve() != folder.absolute():
                raise ValueError('附件路径无效')
            temporary = folder/'upload.part'
            # Uploaded names never become executable commands or overwrite project files.
            with temporary.open('wb') as stream:
                stream.write(data)
            temporary.chmod(0o600)
            temporary.replace(path)
            target = str(path.resolve())
            if self.remote:
                target = self.remote(thread, identifier, name, data, digest)
            row = {'id': identifier, 'name': name, 'size': len(data), 'sha256': digest,
                   'image': image_type(data), 'path': target, 'localPath': str(path.resolve())}
            tmp_meta = meta.with_suffix('.tmp')
            tmp_meta.write_text(json.dumps(row, ensure_ascii=False), encoding='utf-8')
            tmp_meta.chmod(0o600);tmp_meta.replace(meta)
            return self.public(row)

    @staticmethod
    def public(row):
        return {k: row[k] for k in ('id', 'name', 'size', 'image')}

    def get(self, thread, identifier):
        uuid.UUID(thread);uuid.UUID(identifier)
        meta = self.root/thread/(identifier+'.json')
        try:
            row = json.loads(meta.read_text(encoding='utf-8'))
            path = Path(row['localPath'])
            if path.is_symlink() or path.resolve().parent != (self.root/thread/identifier).absolute() or not path.is_file():
                raise ValueError('附件已不可用，请重新上传')
            return row
        except (OSError, KeyError):
            raise ValueError('附件已不可用，请重新上传') from None

    def resolve(self, thread, identifiers):
        if not isinstance(identifiers, list) or len(identifiers) > MAX_FILES or any(not isinstance(v, str) for v in identifiers) or len(set(identifiers)) != len(identifiers):
            raise ValueError('每条消息最多添加 10 个不同附件')
        rows = [self.get(thread, value) for value in identifiers]
        if sum(row['size'] for row in rows) > MAX_TOTAL:
            raise ValueError('每条消息的附件总计不能超过 100 MB')
        return rows
