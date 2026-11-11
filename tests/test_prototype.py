import unittest
from prototype_agent import PrototypeAgent

class FakeProvider:
    def __init__(self, replies): self.replies = iter(replies); self.calls = []
    def send_message(self, message, role='user', system_prefix=''):
        self.calls.append((message, role, system_prefix))
        return {'success': True, 'response': next(self.replies)}

class PrototypeTests(unittest.TestCase):
    def test_two_turns_do_not_resend_history_or_prompt(self):
        p = FakeProvider(['Bonjour', 'Suite']); a = PrototypeAgent(p)
        self.assertTrue(a.send('salut')['ok']); self.assertTrue(a.send('encore')['ok'])
        self.assertTrue(p.calls[0][2]); self.assertEqual(p.calls[1], ('encore', 'user', ''))
    def test_tool_feedback(self):
        p = FakeProvider(['```json\n{"tool":"echo","parameters":{"text":"bonjour"}}\n```', 'Résultat : bonjour'])
        r = PrototypeAgent(p).send('teste echo')
        self.assertTrue(r['ok']); self.assertIn('echo exécuté', r['steps']); self.assertEqual(p.calls[1][1], 'tool_result')
        self.assertIn('bonjour', p.calls[1][0])
    def test_unknown_tool_refused(self):
        p = FakeProvider(['```json\n{"tool":"bash","parameters":{"command":"echo danger"}}\n```', 'Outil indisponible'])
        r = PrototypeAgent(p).send('test')
        self.assertTrue(r['ok']); self.assertIn('Outil non disponible', r['steps'][0])

if __name__ == '__main__': unittest.main()
