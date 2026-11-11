"""Small Android adapter around NEMESIS's original Python provider and parser.

This MVP intentionally exposes only a harmless diagnostic tool. Work/Code and
NEMESIS's complete original executor are not yet ported to Android.
"""
import json
from action_parser import ActionParser
from providers.nemapi_v3 import NemapiV3Provider

SYSTEM = '''You are NEMESIS, a helpful assistant. Reply in the user's language.
For this prototype, the only tool is echo with parameters {"text": string}.
If the user asks you to test the echo tool, call it using a fenced JSON block:
```json
{"tool":"echo","parameters":{"text":"hello"}}
```
Wait for TOOL RESULT and then answer using that result. Never claim that an
unavailable tool was executed. You cannot execute shell commands or access files.'''

class PrototypeAgent:
    def __init__(self, provider=None):
        self.provider = provider or NemapiV3Provider({
            'provider': {'type': 'nemapi', 'model': 'deepseek-chat'},
            'nemapi': {'url': 'http://127.0.0.1:8090/v1', 'model': 'deepseek-chat', 'agent': 'nemesis', 'timeout': 180},
        })
        self.parser = ActionParser(extra_valid_tools={'echo'}, strict=True)
        self.first = True

    def send(self, message):
        current, role = message.strip(), 'user'
        if not current:
            return {'ok': False, 'error': 'Message vide.'}
        steps = []
        for _ in range(4):
            prefix = SYSTEM if self.first else ''
            result = self.provider.send_message(current, role=role, system_prefix=prefix)
            if not result.get('success'):
                return {'ok': False, 'error': str(result.get('error', 'Erreur NemApi'))}
            self.first = False
            raw = result.get('response', '')
            parsed = self.parser.parse(raw)
            if parsed.get('protocol_error'):
                current, role = parsed['protocol_error'], 'tool_result'
                steps.append('Appel invalide refusé')
                continue
            actions = parsed.get('actions') or ([parsed['action']] if parsed.get('action') else [])
            if not actions:
                return {'ok': True, 'text': parsed.get('text') or raw, 'steps': steps}
            feedback = []
            for action in actions:
                name = action.get('type', '')
                args = action.get('content') or {}
                if name == 'echo' and isinstance(args, dict) and isinstance(args.get('text'), str):
                    text = args['text'][:2000]
                    feedback.append('FEEDBACK: ' + json.dumps({'tool': 'echo', 'success': True, 'output': text}, ensure_ascii=False))
                    steps.append('echo exécuté')
                else:
                    feedback.append('FEEDBACK: ' + json.dumps({'tool': name, 'success': False, 'error': 'Outil non disponible dans ce prototype'}, ensure_ascii=False))
                    steps.append('Outil non disponible : ' + str(name))
            current, role = '\n'.join(feedback), 'tool_result'
        return {'ok': False, 'error': 'Arrêt après 4 étapes : boucle outil incomplète.', 'steps': steps}

_agent = PrototypeAgent()

def send(message):
    try:
        return json.dumps(_agent.send(message), ensure_ascii=False)
    except Exception as exc:
        return json.dumps({'ok': False, 'error': type(exc).__name__ + ': ' + str(exc)}, ensure_ascii=False)
