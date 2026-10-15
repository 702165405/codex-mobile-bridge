"""Project source task timestamps; never start a clock when a client opens a chat."""
import math


def milliseconds(value):
    if type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 8_640_000_000_000_000:
        return int(value)
    return None


def turn_timing(turn):
    status = turn.get('status')
    result = {'status': status}
    start = milliseconds(turn.get('turnStartedAtMs'))
    end = milliseconds(turn.get('turnCompletedAtMs'))
    duration = milliseconds(turn.get('turnDurationMs'))
    if start is not None:
        result['startedAtMs'] = start
    if status in ('completed', 'failed', 'interrupted'):
        if end is not None and (start is None or end >= start):
            result['completedAtMs'] = end
            if start is not None:
                duration = end - start
        if duration is not None:
            result['durationMs'] = duration
    return result
