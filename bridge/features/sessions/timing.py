"""Read source start times and final durations; never time from opening a chat."""
import math


def milliseconds(value):
    if type(value) in (int, float) and 0 <= value <= 8_640_000_000_000_000 and math.isfinite(value):
        return int(value)
    return None


def turn_timing(turn):
    status = turn.get('status')
    if status == 'inProgress':
        start = milliseconds(turn.get('turnStartedAtMs'))
        return {'status': status, 'startedAtMs': start} if start is not None else {}
    if status not in ('completed', 'failed', 'interrupted'):
        return {}
    duration = milliseconds(turn.get('durationMs'))
    if duration is None:
        duration = milliseconds(turn.get('turnDurationMs'))
    if duration is None:
        start = milliseconds(turn.get('turnStartedAtMs'))
        end = milliseconds(turn.get('turnCompletedAtMs'))
        if start is not None and end is not None and end >= start:
            duration = end - start
    return {'status': status, 'durationMs': duration} if duration is not None else {}
