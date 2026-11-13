import base64, json, unittest
from unittest.mock import patch, Mock
import mobile_agent as m

class ConnectorTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.tmp=tempfile.TemporaryDirectory(); m.configure(self.tmp.name)
    def tearDown(self): self.tmp.cleanup()

    def test_activation_is_scoped_to_mode_and_session(self):
        work=json.loads(m.create_session('work'))['id']
        code=json.loads(m.create_session('code'))['id']
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':True,'account':'test'})):
            self.assertTrue(json.loads(m.connector_set_active(work,'google_workspace',True))['ok'])
            self.assertTrue(json.loads(m.connector_set_active(code,'github',True))['ok'])
            self.assertFalse(json.loads(m.connector_set_active(work,'github',True))['ok'])
        self.assertTrue(m.connector_active(work,'google_workspace'))
        self.assertFalse(m.connector_active(code,'google_workspace'))
        self.assertIn('google_drive_search',m._system_prompt('work',work))
        self.assertIn('github_file_read',m._system_prompt('code',code))
        self.assertNotIn('github_file_read',m._system_prompt('work',work))

    def test_unconfigured_connector_cannot_be_enabled(self):
        sid=json.loads(m.create_session('code'))['id']
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':False})):
            result=json.loads(m.connector_set_active(sid,'github',True))
        self.assertFalse(result['ok']); self.assertFalse(m.connector_active(sid,'github'))

    def test_inactive_or_wrong_mode_connector_tool_never_calls_network(self):
        sid=json.loads(m.create_session('work'))['id']
        with patch.object(m,'_connector_tool',side_effect=AssertionError('network dispatch must not happen')):
            result=m._tool('github_repo_list',{},sid)
        self.assertFalse(result['success']); self.assertIn('Active ce connecteur',result['error'])

    def test_github_file_read_validates_path_and_decodes_base64(self):
        fake={'type':'file','size':4,'path':'src/a.txt','sha':'abc','content':base64.b64encode(b'test').decode(),'html_url':'https://github.com/o/r/blob/main/src/a.txt'}
        with patch.object(m,'_github_request',return_value=fake) as request:
            result=m._github_tool('github_file_read',{'owner':'org','repo':'repo','path':'src/a.txt'})
        self.assertEqual(result['content'],'test')
        self.assertIn('contents/src/a.txt',request.call_args.args[1])
        with self.assertRaises(ValueError): m._github_tool('github_file_read',{'owner':'org','repo':'repo','path':'../secret'})

    def test_github_write_requires_commit_message_and_uses_existing_sha(self):
        get={'type':'file','sha':'existing'}
        put=Mock(return_value={'content':{'path':'a.txt','html_url':'https://github.com/o/r/a'},'commit':{'sha':'new'}})
        with patch.object(m,'_github_request',side_effect=[get,put]) as request:
            result=m._github_tool('github_file_write',{'owner':'org','repo':'repo','path':'a.txt','content':'new','message':'Update'})
        self.assertTrue(result['success'])
        self.assertEqual(request.call_args.kwargs['json']['sha'],'existing')
        self.assertEqual(request.call_args.kwargs['json']['content'],base64.b64encode(b'new').decode())
        with self.assertRaises(ValueError): m._github_tool('github_file_write',{'owner':'org','repo':'repo','path':'a.txt','content':'new'})

    def test_google_search_escapes_drive_query_and_limits_results(self):
        response=Mock(); response.json.return_value={'files':[{'id':'1234567890','name':'doc'}]}
        with patch.object(m,'_google_request',return_value=response) as request:
            result=m._google_tool('google_drive_search',{'query':"budget '26"})
        self.assertEqual(len(result['files']),1)
        params=request.call_args.kwargs['params']
        self.assertIn("budget",params['q'])
        with self.assertRaises(ValueError): m._google_tool('google_drive_search',{'query':''})

    def test_google_sheet_creation_validates_rows_and_writes_values(self):
        created=Mock(); created.json.return_value={'spreadsheetId':'1234567890','spreadsheetUrl':'https://docs.google.com/spreadsheets/d/1234567890'}
        with patch.object(m,'_google_request',side_effect=[created,Mock()]) as request:
            result=m._google_tool('google_sheet_create',{'title':'Test','rows':[['A','B'],[1,2]]})
        self.assertTrue(result['success'])
        self.assertEqual(request.call_args.kwargs['json']['values'],[['A','B'],['1','2']])
        with self.assertRaises(ValueError): m._google_tool('google_sheet_create',{'title':'bad','rows':'not rows'})

    def test_connector_prompt_is_injected_mid_session_only_once_per_activation(self):
        sid=json.loads(m.create_session('work'))['id']
        with m._db() as db: db.execute('UPDATE sessions SET conversation_url=? WHERE id=?',('https://chat.deepseek.com/a/chat/s/abc123',sid))
        provider=Mock(); provider.url='https://chat.deepseek.com/a/chat/s/abc123'; provider.send_message.return_value={'success':True,'response':'Done'}
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':True,'account':'work@example.test'})), \
             patch.object(m,'SessionProvider',return_value=provider):
            self.assertTrue(json.loads(m.connector_set_active(sid,'google_workspace',True))['ok'])
            result=json.loads(m.send(sid,'Cherche le document'))
        self.assertTrue(result['ok'])
        sent=provider.send_message.call_args.kwargs['message'] if 'message' in provider.send_message.call_args.kwargs else provider.send_message.call_args.args[0]
        self.assertIn('google_drive_search',sent)
        # Disable/enable should permit a fresh announcement on the next turn.
        provider=Mock(); provider.url='https://chat.deepseek.com/a/chat/s/abc123'; provider.send_message.return_value={'success':True,'response':'Done'}
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':True,'account':'work@example.test'})), \
             patch.object(m,'SessionProvider',return_value=provider):
            m.connector_set_active(sid,'google_workspace',False); m.connector_set_active(sid,'google_workspace',True)
            self.assertTrue(json.loads(m.send(sid,'Encore'))['ok'])
        sent2=provider.send_message.call_args.kwargs.get('message',provider.send_message.call_args.args[0])
        self.assertIn('google_drive_search',sent2)

    def test_disabling_connector_removes_it_from_all_session_prompts(self):
        sid=json.loads(m.create_session('code'))['id']
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':True,'username':'test','account':'test'})):
            self.assertTrue(json.loads(m.connector_set_active(sid,'github',True))['ok'])
        self.assertIn('github_file_read',m._system_prompt('code',sid))
        m._disable_connector_everywhere('github')
        self.assertFalse(m.connector_active(sid,'github'))
        self.assertNotIn('github_file_read',m._system_prompt('code',sid))

    def test_active_connector_enters_first_system_prompt_only_in_its_mode(self):
        sid=json.loads(m.create_session('code'))['id']
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':True,'username':'user','account':'user'})):
            self.assertTrue(json.loads(m.connector_set_active(sid,'github',True))['ok'])
        provider=Mock(); provider.url=None; provider.send_message.return_value={'success':True,'response':'OK'}
        with patch.object(m,'SessionProvider',return_value=provider):
            self.assertTrue(json.loads(m.send(sid,'Inspecte ce dépôt'))['ok'])
        prefix=provider.send_message.call_args.kwargs['system_prefix']
        self.assertIn('github_repo_search',prefix)
        self.assertIn('github_file_write',prefix)
        self.assertNotIn('github_pat_',prefix)

    def test_github_connection_test_checks_token_owner(self):
        with patch.object(m,'connector_status',return_value=json.dumps({'username':'alice'})), \
             patch.object(m,'_github_request',return_value={'login':'alice','name':'Alice'}):
            result=json.loads(m.github_test_config())
        self.assertTrue(result['ok']); self.assertEqual(result['account'],'alice')
        with patch.object(m,'connector_status',return_value=json.dumps({'username':'alice'})), \
             patch.object(m,'_github_request',return_value={'login':'bob'}):
            result=json.loads(m.github_test_config())
        self.assertFalse(result['ok']); self.assertIn('@bob',result['error'])

    def test_google_connection_test_checks_live_drive_api_access(self):
        response=Mock(); response.json.return_value={'files':[]}
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':True,'email':'user@example.test'})), \
             patch.object(m,'_google_request',return_value=response) as request:
            result=json.loads(m.google_test_config())
        self.assertTrue(result['ok']); self.assertEqual(result['service'],'Google Drive API')
        self.assertIn('drive/v3/files',request.call_args.args[1])
        self.assertEqual(request.call_args.kwargs['params']['pageSize'],1)

    def test_google_drive_test_surfaces_missing_oauth_or_api_access(self):
        with patch.object(m,'connector_status',return_value=json.dumps({'configured':True,'email':'user@example.test'})), \
             patch.object(m,'_google_request',side_effect=ValueError('Google API HTTP 403: API disabled')):
            result=json.loads(m.google_test_config())
        self.assertFalse(result['ok']); self.assertIn('403',result['error'])

    def test_google_drive_read_rejects_binary_files_before_download(self):
        meta=Mock(); meta.json.return_value={'id':'1234567890','name':'scan','mimeType':'application/pdf'}
        with patch.object(m,'_google_request',return_value=meta) as request:
            with self.assertRaisesRegex(ValueError,'non pris en charge'):
                m._google_tool('google_drive_read',{'file_id':'1234567890'})
        request.assert_called_once()

    def test_google_drive_read_is_bounded_and_reports_truncation(self):
        meta=Mock(); meta.json.return_value={'id':'1234567890','name':'notes','mimeType':'text/plain'}
        download=Mock(); download.iter_content.return_value=[b'a'*140_000,b'b'*140_000]
        with patch.object(m,'_google_request',side_effect=[meta,download]):
            result=m._google_tool('google_drive_read',{'file_id':'1234567890'})
        self.assertTrue(result['success']); self.assertTrue(result['truncated'])
        self.assertEqual(len(result['content']),200_000); download.close.assert_called_once()
