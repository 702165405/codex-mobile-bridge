import unittest
import uuid
from unittest.mock import patch

import test_bridge as support

THREAD = support.THREAD
from bridge.ipc import IPCError
from bridge.model import normalize_request


class ModeTests(unittest.TestCase):
    setUp = support.IntegrationTests.setUp
    tearDown = support.IntegrationTests.tearDown

    def calls(self):
        return [r for r in self.fixture.requests if r['method'] == 'thread-follower-start-turn']

    def live(self, **changes):
        session = self.bridge.session(THREAD)
        with session.condition:
            session.state.update(changes)
            session.changed()
        return session

    def plan(self):
        request = {'id': 'implement-plan:turn', 'method': 'item/plan/requestImplementation',
                   'params': {'turnId': 'turn', 'planContent': '# Plan\n\nReply OK.'}}
        self.fixture.state['requests'] = [request]
        return request

    def test_plan_mode_is_sent_to_original_owner_and_preserves_model_effort_policy(self):
        self.fixture.state['latestReasoningEffort'] = 'high'
        self.bridge.send(THREAD, 'Make a plan', str(uuid.uuid4()), work_mode='plan')
        call = self.calls()[0]
        self.assertEqual(call['targetClientId'], 'owner')
        self.assertEqual(call['params']['conversationId'], THREAD)
        request = call['params']['turnStart']['request']
        self.assertEqual(request['collaborationMode'], {'mode': 'plan', 'settings': {
            'model': 'same-model', 'reasoning_effort': 'high', 'developer_instructions': None}})
        self.assertTrue(call['params']['turnStart']['context']['inheritThreadSettings'])
        for key in ['modelProvider', 'approvalPolicy', 'sandboxPolicy', 'permissions']:
            self.assertNotIn(key, request)

    def test_mode_is_part_of_idempotency_and_legacy_send_inherits(self):
        key = str(uuid.uuid4())
        self.bridge.send(THREAD, 'hello', key, work_mode='plan')
        self.bridge.send(THREAD, 'hello', key, work_mode='plan')
        with self.assertRaises(ValueError):
            self.bridge.send(THREAD, 'hello', key, work_mode='default')
        self.bridge.send(THREAD, 'legacy', str(uuid.uuid4()))
        self.assertNotIn('collaborationMode', self.calls()[-1]['params']['turnStart']['request'])
        self.assertEqual(len(self.calls()), 2)

    def test_remote_mode_keeps_host_and_native_version(self):
        self.bridge.host = self.fixture.host = 'remote:test'
        self.bridge.send(THREAD, 'Plan remotely', str(uuid.uuid4()), work_mode='plan')
        self.assertEqual(self.calls()[0]['hostId'], 'remote:test')
        self.assertEqual(self.calls()[0]['version'], 3)

    def test_queued_mode_survives_desktop_mode_change(self):
        self.fixture.state['threadRuntimeStatus'] = {'type': 'active'}
        key = str(uuid.uuid4())
        self.bridge.send(THREAD, 'plan later', key, 'queue', work_mode='plan')
        self.assertEqual(self.calls(), [])
        session = self.live(threadRuntimeStatus={'type': 'idle'}, latestCollaborationMode={'mode': 'default'}, latestModel='new-model')
        entry = self.bridge.submissions[THREAD + ':' + key]
        self.bridge._send_queued(session, THREAD + ':' + key, entry)
        settings = self.calls()[0]['params']['turnStart']['request']['collaborationMode']
        self.assertEqual(settings['mode'], 'plan')
        self.assertEqual(settings['settings']['model'], 'new-model')

    def test_goal_requests_native_tool_and_does_not_claim_activation(self):
        key = str(uuid.uuid4())
        result = self.bridge.send(THREAD, 'Reply GOAL_OK', key, work_mode='goal')
        self.assertEqual(result['status'], 'accepted')
        request = self.calls()[0]['params']['turnStart']['request']
        self.assertEqual(request['collaborationMode']['mode'], 'default')
        self.assertIn('create_goal', request['input'][0]['text'])
        self.assertTrue(request['input'][0]['text'].endswith('Reply GOAL_OK'))
        view = self.bridge.view(THREAD)
        self.assertIsNone(view['goal'])
        self.assertEqual(view['goalSubmission']['status'], 'pending')
        self.assertEqual(self.bridge.timeline_read(THREAD)['meta']['goalSubmission']['status'], 'pending')
        self.bridge.send(THREAD, 'Reply GOAL_OK', key, work_mode='goal')
        with self.assertRaises(ValueError):
            self.bridge.send(THREAD, 'Reply GOAL_OK', str(uuid.uuid4()), work_mode='goal')
        self.assertEqual(len(self.calls()), 1)

    def test_actual_native_goal_and_completion_are_exposed(self):
        self.bridge.send(THREAD, 'Reply OK', str(uuid.uuid4()), work_mode='goal')
        goal = {'objective': 'Reply OK', 'status': 'active', 'tokensUsed': 50}
        self.live(threadGoal=goal, latestCollaborationMode={'mode': 'default'})
        view = self.bridge.timeline_read(THREAD)['meta']
        self.assertEqual(view['goal'], goal)
        self.assertIsNone(view['goalSubmission'])
        self.live(threadGoal=None, completedThreadGoal={**goal, 'status': 'complete'})
        self.assertEqual(self.bridge.view(THREAD)['goal']['status'], 'complete')
        self.assertIsNone(self.bridge.view(THREAD)['goalSubmission'])
        self.live(threadGoal=None, completedThreadGoal=None)
        self.assertIsNone(self.bridge.view(THREAD)['goalSubmission'])

    def test_old_completed_goal_does_not_confirm_new_goal(self):
        goal = {'objective': 'Same objective', 'status': 'complete'}
        self.fixture.state['completedThreadGoal'] = goal
        self.bridge.send(THREAD, 'Same objective', str(uuid.uuid4()), work_mode='goal')
        self.assertEqual(self.bridge.view(THREAD)['goalSubmission']['status'], 'pending')

    def test_goal_tool_unavailable_leaves_unconfirmed_status(self):
        key = str(uuid.uuid4())
        self.bridge.send(THREAD, 'Reply OK', key, work_mode='goal')
        self.live(turns=[{'turnId': 'new', 'status': 'completed', 'params': {'clientUserMessageId': key}, 'items': []}])
        self.assertEqual(self.bridge.view(THREAD)['goalSubmission']['status'], 'unconfirmed')
        self.assertIsNone(self.bridge.view(THREAD)['goal'])

    def test_goal_unknown_delivery_is_not_replayed(self):
        key = str(uuid.uuid4())
        self.bridge.session(THREAD)
        with patch.object(self.bridge, '_call', side_effect=IPCError('timeout')) as call:
            with self.assertRaises(IPCError):
                self.bridge.send(THREAD, 'Reply OK', key, work_mode='goal')
            result = self.bridge.send(THREAD, 'Reply OK', key, work_mode='goal')
        self.assertEqual(call.call_count, 1)
        self.assertEqual(result['status'], 'unknown')
        self.assertEqual(self.bridge.view(THREAD)['goalSubmission']['status'], 'unknown')

    def test_goal_validation_blocks_replacement_and_steering(self):
        for mode,work,text in [('send','bad','a'),('steer','plan','a'),('queue','goal','a'),('send','goal','a'*4001)]:
            with self.assertRaises(ValueError):
                self.bridge.send(THREAD, text, str(uuid.uuid4()), mode, work_mode=work)
        self.fixture.state['threadGoal'] = {'objective': 'old', 'status': 'paused'}
        with self.assertRaises(ValueError):
            self.bridge.send(THREAD, 'new', str(uuid.uuid4()), work_mode='goal')
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.bridge.submissions, {})

    def test_missing_model_fails_before_submission_ledger(self):
        self.fixture.state['latestModel'] = ''
        with self.assertRaises(ValueError):
            self.bridge.send(THREAD, 'plan', str(uuid.uuid4()), work_mode='plan')
        self.assertEqual(self.bridge.submissions, {})

    def test_plan_request_renders_content_but_user_verification_stays_protected(self):
        request = self.plan()
        normalized = normalize_request(request)
        self.assertTrue(normalized['supported'])
        self.assertEqual(normalized['params']['planContent'], request['params']['planContent'])
        request['params']['_meta'] = {'openai/userVerification': True}
        self.assertFalse(normalize_request(request)['supported'])

    def test_plan_implementation_uses_content_and_default_mode_once(self):
        request = self.plan()
        result = self.bridge.respond(THREAD, request['id'], {'action': 'implement'})
        self.assertEqual(result['status'], 'accepted')
        payload = self.calls()[0]['params']['turnStart']['request']
        self.assertTrue(payload['input'][0]['text'].endswith(request['params']['planContent']))
        self.assertEqual(payload['collaborationMode']['mode'], 'default')
        self.live(requests=[])
        self.assertTrue(self.bridge.respond(THREAD, request['id'], {'action': 'implement'})['duplicate'])
        self.assertEqual(len(self.calls()), 1)
        with self.assertRaises(ValueError):
            self.bridge.respond(THREAD, request['id'], {'action': 'revise', 'text': 'different'})

    def test_plan_revision_keeps_plan_mode_and_rejects_empty_feedback(self):
        request = self.plan()
        with self.assertRaises(ValueError):
            self.bridge.respond(THREAD, request['id'], {'action': 'revise', 'text': ' '})
        self.bridge.respond(THREAD, request['id'], {'action': 'revise', 'text': 'Make it shorter'})
        payload = self.calls()[0]['params']['turnStart']['request']
        self.assertEqual(payload['input'][0]['text'], 'Make it shorter')
        self.assertEqual(payload['collaborationMode']['mode'], 'plan')

    def test_stale_plan_cannot_start_a_turn(self):
        with self.assertRaises(ValueError):
            self.bridge.respond(THREAD, 'implement-plan:stale', {'action': 'implement'})
        self.assertEqual(self.calls(), [])

    def test_uncertain_plan_execution_is_not_replayed(self):
        request = self.plan()
        self.bridge.session(THREAD)
        with patch.object(self.bridge, '_call', side_effect=IPCError('timeout')) as call:
            with self.assertRaises(IPCError):
                self.bridge.respond(THREAD, request['id'], {'action': 'implement'})
            result = self.bridge.respond(THREAD, request['id'], {'action': 'implement'})
        self.assertEqual(call.call_count, 1)
        self.assertEqual(result['status'], 'unknown')


if __name__ == '__main__':
    unittest.main()
