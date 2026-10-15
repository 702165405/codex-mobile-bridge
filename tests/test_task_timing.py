import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from bridge.app.service import LiveSession
from bridge.clients.claude.model import transcript
from bridge.features.sessions.model import normalize_state, apply_patches
from bridge.features.sessions.store import SessionStore, timestamp_ms
from bridge.features.sessions.timeline import Timeline
from bridge.features.sessions.timing import turn_timing


class TaskTimingTests(unittest.TestCase):
    def test_source_times_are_validated_and_terminal_duration_never_uses_now(self):
        self.assertEqual(turn_timing({'status': 'inProgress', 'turnStartedAtMs': 1000, 'durationMs': 118000}),
                         {'status': 'inProgress', 'startedAtMs': 1000})
        self.assertEqual(turn_timing({'status': 'completed', 'turnStartedAtMs': 1792056063883, 'durationMs': 21988}),
                         {'status': 'completed', 'durationMs': 21988})
        self.assertEqual(turn_timing({'status': 'completed', 'turnStartedAtMs': 1000, 'turnCompletedAtMs': 62500})['durationMs'], 61500)
        self.assertEqual(turn_timing({'status': 'completed', 'durationMs': 118567, 'turnStartedAtMs': 1000, 'turnCompletedAtMs': 119000})['durationMs'], 118567)
        for status in ('completed', 'failed', 'interrupted'):
            self.assertEqual(turn_timing({'status': status, 'durationMs': 0})['durationMs'], 0)
            self.assertEqual(turn_timing({'status': status, 'turnDurationMs': 0})['durationMs'], 0)
            self.assertNotIn('durationMs', turn_timing({'status': status, 'turnStartedAtMs': 1000}))
        for invalid in (True, -1, float('inf'), float('nan'), '1000', 10 ** 500):
            self.assertEqual(turn_timing({'status': 'completed', 'durationMs': invalid}), {})
        self.assertNotIn('durationMs', turn_timing({'status': 'completed', 'turnStartedAtMs': 2000, 'turnCompletedAtMs': 1000}))
        self.assertIsNone(timestamp_ms('2026-10-15T12:00:00'))
        self.assertIsNone(timestamp_ms('not a date'))
        self.assertEqual(timestamp_ms('2026-10-15T12:00:00Z'), timestamp_ms('2026-10-15T20:00:00+08:00'))

    def test_timeline_timing_updates_without_changing_message_keys_or_text_versions(self):
        turn = {'turnId': 't', 'status': 'inProgress', 'turnStartedAtMs': 1000, 'items': [
            {'id': 'u', 'type': 'userMessage', 'content': [{'type': 'input_text', 'text': 'Run'}]},
            {'id': 'a', 'type': 'agentMessage', 'text': 'Done'}]}
        timeline = Timeline()
        timeline.update({**normalize_state({'turns': [turn]}), 'sequence': 1})
        first = timeline.page()
        self.assertTrue(all('timing' not in row for row in first['rows']))
        self.assertEqual(first['meta']['taskTiming'], {'status': 'inProgress', 'startedAtMs': 1000})
        keys = [(row['key'], row['version']) for row in first['rows']]
        turn.update(status='completed', durationMs=118000)
        timeline.update({**normalize_state({'turns': [turn]}), 'sequence': 2})
        self.assertEqual(keys, [(row['key'], row['version']) for row in timeline.rows])
        changes = timeline.changes(1, first['epoch'], first['before'])
        footer = next(row for row in changes['rows'] if 'timing' in row)
        self.assertEqual(footer['role'], 'assistant')
        self.assertEqual(footer['timing']['durationMs'], 118000)
        self.assertEqual(sum('timing' in row for row in timeline.rows), 1)
        self.assertNotIn('taskTiming', changes['meta'])
        no_user = normalize_state({'turns': [{**turn, 'items': turn['items'][1:]}]})
        timeline.update({**no_user, 'sequence': 3})
        self.assertIn('timing', timeline.rows[-1])

    def test_saved_rollout_timestamps_survive_live_history_hydration(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(dir=root / '.tmp') as directory:
            home = Path(directory)
            rollout = home / 'sessions' / 'timing.jsonl'
            rollout.parent.mkdir()
            records = [
                {'timestamp': '2026-10-15T12:00:00Z', 'type': 'event_msg', 'payload': {'type': 'task_started', 'turn_id': 't'}},
                {'type': 'response_item', 'payload': {'type': 'message', 'role': 'assistant', 'content': [{'text': 'Done'}]}},
                {'timestamp': '2026-10-15T12:01:02.500Z', 'type': 'event_msg', 'payload': {'type': 'task_complete', 'duration_ms': 62567}}]
            rollout.write_text('\n'.join(json.dumps(record) for record in records), encoding='utf-8')
            store = SessionStore(home)
            with patch.object(store, 'get', return_value={'rollout_path': str(rollout), 'cwd': '/fixture'}):
                saved = store.history('fixture')
            session = LiveSession('fixture')
            with session.condition:
                session.set_history(saved)
                session.state = {'turns': [{'turnId': 't', 'status': 'completed', 'items': []}]}
                session.connected = True
            self.assertEqual(session.view()['turns'][0]['timing']['durationMs'], 62567)
            session.state['turns'][0]['status'] = 'inProgress'
            self.assertEqual(session.view()['turns'][0]['timing'], {})

    def test_live_completion_patch_adds_footer_without_reopening_or_saved_history(self):
        session = LiveSession('fixture')
        with session.condition:
            session.state = {'turns': [{'turnId': 'live', 'status': 'inProgress', 'turnStartedAtMs': 1792056063883,
                                       'durationMs': None, 'items': [
                                           {'id': 'comment', 'type': 'agentMessage', 'phase': 'commentary', 'text': 'Working'},
                                           {'id': 'answer', 'type': 'agentMessage', 'phase': 'final_answer', 'text': 'Done'}]}]}
            session.connected = True
            session.timeline.update(session.view())
            first = session.timeline.page()
            self.assertTrue(all('timing' not in row for row in first['rows']))
            session.state = apply_patches(session.state, [
                {'op': 'replace', 'path': ['turns', 0, 'status'], 'value': 'completed'}])
            session.changed()
            session.timeline.update(session.view())
            completed = session.timeline.page()
            self.assertTrue(all('timing' not in row for row in completed['rows']))
            session.state = apply_patches(session.state, [
                {'op': 'replace', 'path': ['turns', 0, 'durationMs'], 'value': 118000}])
            session.changed()
            session.timeline.update(session.view())
            changed = session.timeline.changes(completed['sequence'], completed['epoch'], completed['before'])
            self.assertEqual(len(changed['rows']), 1)
            footers = [row for row in changed['rows'] if 'timing' in row]
            self.assertEqual(len(footers), 1)
            self.assertEqual(footers[0]['text'], 'Done')
            self.assertEqual(footers[0]['timing']['durationMs'], 118000)

    def test_claude_only_displays_authoritative_root_result_duration(self):
        assistant = {'type': 'assistant', 'uuid': 'a', 'message': {'role': 'assistant', 'content': [{'type': 'text', 'text': 'Done'}]}}
        result = {'type': 'result', 'uuid': 'r', 'subtype': 'success', 'is_error': False, 'num_turns': 1, 'duration_ms': 12345}
        messages = transcript([assistant, result])
        self.assertEqual(messages[-1]['timing'], {'status': 'completed', 'durationMs': 12345})
        self.assertNotIn('timing', transcript([assistant, {**result, 'parent_tool_use_id': 'child'}])[-1])
        user = {'type': 'user', 'message': {'role': 'user', 'content': 'Next'}}
        self.assertTrue(all('timing' not in row for row in transcript([assistant, user, result])))
