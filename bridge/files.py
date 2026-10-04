"""Serve only artifacts referenced by this chat inside its workspace/visualizations."""
import hashlib
import ntpath
import re
from pathlib import Path, PureWindowsPath
from urllib.parse import unquote, urlsplit
from urllib.request import url2pathname

from .model import ordered_turns, items_array

MARKDOWN_PATH = re.compile(r'!?\[[^\]\n]*\]\((?:<([^>]+)>|([^\n)]+))\)')
IMAGE_MARKDOWN_PATH = re.compile(r'!\[[^\]\n]*\]\((?:<([^>]+)>|([^\n)]+))\)')
AGENT_KINDS = ('agentMessage', 'assistantMessage', 'planImplementation')
IMAGE_SUFFIXES = {'.png', '.jpg', '.jpeg', '.gif', '.webp'}


def reference_path(value):
    """Resolve a local file reference without allowing remote URL access."""
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = urlsplit(value)
        if parsed.scheme == 'file':
            if parsed.netloc not in ('', 'localhost') or not parsed.path:
                return None
            file_path = unquote(parsed.path)
            # On Windows, file:///D:/... has a POSIX-style leading slash.
            # Strip it before translating so the result has the D: drive.
            if re.fullmatch(r'/[A-Za-z]:[\\/].+', file_path):
                file_path = file_path[1:]
            return Path(url2pathname(file_path))
        drive, tail = ntpath.splitdrive(value)
        if drive:
            candidate = PureWindowsPath(drive + tail)
            if not candidate.is_absolute():
                return None
            # On Windows this is a concrete WindowsPath. On POSIX this stays a
            # relative drive path and is rejected by the same is_absolute gate.
            return Path(str(candidate))
        if parsed.scheme:
            return None
        path = Path(unquote(parsed.path or value))
        return path if path.is_absolute() else None
    except (ValueError, OSError):
        return None


def referenced_model_images(state):
    """Map image paths explicitly shown or embedded by model output for this thread."""
    result = {}
    def add(value, reference):
        # Remove an editor line suffix without changing the reference shown by
        # Markdown; only the actual filesystem lookup uses the trimmed path.
        path = reference_path(re.sub(r':\d+$', '', value))
        if not path or not (path.is_absolute() or bool(ntpath.splitdrive(str(path))[0])):
            return
        row = result.setdefault(path.resolve(), set())
        if isinstance(reference, str) and reference:
            row.add(reference)

    for turn in ordered_turns(state):
        for item in items_array(turn.get('items')):
            kind = item.get('type')
            if kind in ('ImageView', 'imageView'):
                add(item.get('path'), item.get('path'))
            elif kind in AGENT_KINDS:
                text = item.get('text', '')
                if isinstance(text, str):
                    for match in IMAGE_MARKDOWN_PATH.finditer(text):
                        reference = match[1] or match[2]
                        add(reference, reference)
    return result


def artifact_paths(state, codex_home):
    roots = [Path(codex_home) / 'visualizations']
    if state.get('cwd'):
        roots.append(Path(state['cwd']))
    roots = [root.resolve() for root in roots]
    candidates = set()
    for turn in ordered_turns(state):
        for item in items_array(turn.get('items')):
            if item.get('type') in ('agentMessage', 'assistantMessage'):
                for match in MARKDOWN_PATH.finditer(item.get('text', '')):
                    candidates.add(match[1] or match[2])
            for attachment in item.get('content', []) if isinstance(item.get('content'), list) else []:
                if isinstance(attachment, dict) and attachment.get('type') in ('localImage', 'image', 'file'):
                    value = attachment.get('path', attachment.get('url'))
                    if isinstance(value, str):
                        candidates.add(value)
    result = {}
    model_images = referenced_model_images(state)
    for raw in candidates:
        # Preserve the displayed link while removing an editor line suffix from the path.
        value = re.sub(r':\d+$', '', raw)
        path = reference_path(value)
        if not path:
            continue
        try:
            path = path.resolve(strict=True)
            is_model_image = path in model_images and path.suffix.lower() in IMAGE_SUFFIXES
            if ((not is_model_image and not any(root in path.parents for root in roots))
                    or not path.is_file() or path.stat().st_size > 50 * 1024 * 1024):
                continue
        except OSError:
            continue
        key = hashlib.sha256(str(path).encode()).hexdigest()
        result[key] = {'path': path, 'reference': raw, 'name': path.name,
                       'image': path.suffix.lower() in IMAGE_SUFFIXES}
    # A path can be shown once as ImageView and embedded later with another text
    # form (usually a plain absolute path versus file://). Prefer the exact
    # Markdown reference so renderMarkdown can resolve it to this artifact ID.
    for path, references in model_images.items():
        try:
            if not path.is_file() or path.stat().st_size > 50 * 1024 * 1024:
                continue
        except OSError:
            continue
        plain = sorted((ref for ref in references if reference_path(ref) == path and not urlsplit(ref).scheme),
                       key=lambda value: (len(value), value))
        file_refs = sorted(ref for ref in references if urlsplit(ref).scheme == 'file')
        reference = plain[0] if plain else (file_refs[0] if file_refs else next(iter(references)))
        key = hashlib.sha256(str(path).encode()).hexdigest()
        result[key] = {'path': path, 'reference': reference, 'name': path.name,
                       'image': path.suffix.lower() in IMAGE_SUFFIXES}
    return result
