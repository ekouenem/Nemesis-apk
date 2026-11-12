import base64, io, json, tempfile, time, unittest, zipfile
from pathlib import Path
from unittest.mock import Mock, patch
import mobile_agent as m
from src.core.tools import web_search as w

class Beta5Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.root=Path(self.tmp.name); self.shared=self.root/'Nemesis'
        m.configure(str(self.root/'private'),8090,'',str(self.shared))
    def tearDown(self): self.tmp.cleanup()
    def new(self,mode='work'): return json.loads(m.create_session(mode))['id']
    def test_search_rss_and_fallback(self):
        rss=b'<rss><channel><item><title>Exemple</title><link>https://example.org/</link><description>Texte &amp; nouvelles</description></item></channel></rss>'
        with patch.object(w,'_get',return_value=rss):
            results=w._bing_rss_search('Exemple',5)
        self.assertEqual(results[0]['snippet'],'Texte & nouvelles'); self.assertEqual(results[0]['url'],'https://example.org/')
        with patch.object(w,'_wiki_search',return_value=[]),patch.object(w,'_bing_rss_search',side_effect=RuntimeError('blocked')),patch.object(w,'_ddg_html_search',return_value=results):
            self.assertTrue(w.web_search(w.WebSearchInput('hello')).success)
        with patch.object(w,'_wiki_search',return_value=[]),patch.object(w,'_bing_rss_search',return_value=[]),patch.object(w,'_ddg_html_search',return_value=[]):
            self.assertFalse(w.web_search(w.WebSearchInput('hello')).success)
        with patch.object(w,'_get',return_value=b'<!DOCTYPE rss><rss/>'):
            with self.assertRaises(RuntimeError): w._bing_rss_search('x',5)
    def test_names_migrate_and_rename_without_overwrite(self):
        a,b=self.new(),self.new(); self.assertTrue(json.loads(m.rename_session(a,'Projet / Alpha'))['ok']); self.assertTrue(json.loads(m.rename_session(b,'Projet / Alpha'))['ok'])
        with m._db() as db: db.execute("UPDATE sessions SET workspace_name='',workspace_initialized=0 WHERE id IN (?,?)",(a,b))
        legacy=self.shared/'Work'/a; legacy.mkdir(); (legacy/'note.txt').write_text('original')
        occupied=self.shared/'Work'/'Projet Alpha'; occupied.mkdir(); (occupied/'other').write_text('untouched')
        m.configure(str(self.root/'private'),8090,'',str(self.shared))
        first=Path(m.workspace_root(a)); second=Path(m.workspace_root(b))
        self.assertNotEqual(first,second); self.assertNotEqual(first,occupied); self.assertFalse(legacy.exists())
        self.assertEqual((first/'note.txt').read_text(),'original'); self.assertFalse(second.exists()); self.assertEqual((occupied/'other').read_text(),'untouched')
        self.assertTrue(json.loads(m.rename_session(a,'Nouveau nom'))['ok'])
        new=Path(m.workspace_root(a)); self.assertEqual(new.name,'Nouveau nom'); self.assertEqual((new/'note.txt').read_text(),'original')
        self.assertFalse(first.exists()); self.assertFalse(m._tool('read_file',{'path':'../outside'},a)['success'])
    def test_external_directory_does_not_become_session_workspace(self):
        sid=self.new(); m.rename_session(sid,'Projet'); reserved=Path(m.workspace_root(sid))
        reserved.mkdir(parents=True); (reserved/'user.txt').write_text('conserver')
        self.assertTrue(m._tool('write_file',{'path':'test.txt','content':'x'},sid)['success'])
        actual=Path(m.workspace_root(sid)); self.assertNotEqual(actual,reserved)
        self.assertEqual((reserved/'user.txt').read_text(),'conserver'); self.assertEqual((actual/'test.txt').read_text(),'x')
    def test_recover_rename_interrupted_before_and_after_filesystem_move(self):
        sid=self.new(); m.rename_session(sid,'Avant'); root=m._ensure_workspace(sid)
        (root/'important.txt').write_text('intact')
        with patch.object(m,'_finish_workspace_move',side_effect=OSError('interruption')):
            self.assertFalse(json.loads(m.rename_session(sid,'Après'))['ok'])
        self.assertTrue(root.exists())
        m.configure(str(self.root/'private'),8090,'',str(self.shared))
        new=Path(m.workspace_root(sid)); self.assertEqual(new.name,'Après'); self.assertEqual((new/'important.txt').read_text(),'intact')
        pending={'old':'Après','new':'Fin','title':'Fin'}
        with m._db() as db: db.execute('UPDATE sessions SET workspace_pending=? WHERE id=?',(json.dumps(pending),sid))
        new.rename(new.parent/'Fin') # crash after move, before DB update
        m.configure(str(self.root/'private'),8090,'',str(self.shared))
        self.assertEqual(Path(m.workspace_root(sid)).name,'Fin'); self.assertEqual((Path(m.workspace_root(sid))/'important.txt').read_text(),'intact')
        self.assertEqual(json.loads(m.get_session(sid))['title'],'Fin')
    def test_remove_only_empty_legacy_uuid_session_folder(self):
        sid=self.new(); legacy=self.shared/'Work'/sid; legacy.mkdir()
        self.assertEqual(json.loads(m.list_files(sid)),[])
        self.assertFalse(legacy.exists()); self.assertFalse(Path(m.workspace_root(sid)).exists())
    def test_local_office_and_media_work_in_both_modes(self):
        for mode in ('work','code'):
            sid=self.new(mode)
            self.assertTrue(m._tool('write_file',{'path':'local.txt','content':'Bonjour'},sid)['success'])
            self.assertEqual(m._tool('read_file',{'path':'local.txt'},sid)['content'],'Bonjour')
            self.assertTrue(m._tool('list_dir',{'path':'.'},sid)['success'])
            root=Path(m.workspace_root(sid)); (root/'image.png').write_bytes(b'\x89PNG\r\n\x1a\nfake')
            media=m._tool('read_file',{'path':'image.png'},sid)
            self.assertTrue(media['success']); self.assertTrue(media['attachment_required']); self.assertEqual(media['mime'],'image/png')
            (root/'doc.pdf').write_bytes(b'%PDF-1.4 fake')
            self.assertEqual(m._tool('read_file',{'path':'doc.pdf'},sid)['mime'],'application/pdf')
            (root/'audio.mp3').write_bytes(b'ID3fake')
            self.assertFalse(m._tool('read_file',{'path':'audio.mp3'},sid)['success'])
            with zipfile.ZipFile(root/'doc.docx','w') as z: z.writestr('word/document.xml','<doc><p><t>Texte du document</t></p></doc>')
            doc=m._tool('read_file',{'path':'doc.docx'},sid)
            self.assertTrue(doc['success']); self.assertIn('Texte du document',doc['content'])
    def test_tool_read_sends_real_file_with_feedback_not_base64_in_history(self):
        for mode in ('work','code'):
            sid=self.new(mode); root=m._ensure_workspace(sid); (root/'photo.png').write_bytes(b'\x89PNG test')
            action=json.dumps({'tool':'read_file','parameters':{'path':'photo.png'}})
            provider=Mock();provider.url=None
            calls=[]
            def reply(*args,**kwargs):
                calls.append(kwargs)
                if len(calls)==1: return {'success':True,'response':'```json\n'+action+'\n```'}
                part=kwargs['images'][0]; self.assertEqual(part['type'],'file')
                self.assertEqual(Path(part['file']['local_path']).read_bytes(),b'\x89PNG test')
                return {'success':True,'response':'Description'}
            provider.send_message.side_effect=reply
            with patch.object(m,'SessionProvider',return_value=provider): self.assertTrue(json.loads(m.send(sid,'Décris'))['ok'])
            temporary=Path(calls[1]['images'][0]['file']['local_path']); self.assertFalse(temporary.exists())
            history=m.get_session(sid); self.assertNotIn(base64.b64encode(b'\x89PNG test').decode(),history)
    def test_pdf_50m_boundary_and_history_lifetime(self):
        sid=self.new('chat'); folder=self.root/'private'/'attachments'; folder.mkdir(exist_ok=True)
        pdf=folder/'large';
        with pdf.open('wb') as f: f.write(b'%PDF-1.4\n'); f.truncate(50*1024*1024)
        provider=Mock(); provider.url=None; provider.send_message.return_value={'success':True,'response':'OK'}
        attachment=[{'name':'test.pdf','mime':'application/pdf','path':str(pdf)}]
        with patch.object(m,'SessionProvider',return_value=provider): self.assertTrue(json.loads(m.send(sid,'Lire',json.dumps(attachment)))['ok'])
        part=provider.send_message.call_args.kwargs['images'][0]['file']
        self.assertEqual(part['local_path'],str(pdf)); self.assertNotIn('file_data',part)
        msg=json.loads(m.get_session(sid))['messages'][0]; self.assertEqual(msg['attachments'][0]['size'],50*1024*1024)
        self.assertTrue(pdf.exists()); m.delete_session(sid); self.assertFalse(pdf.exists())
        sid=self.new('chat'); pdf=folder/'big';
        with pdf.open('wb') as f: f.write(b'%PDF-1.4\n'); f.truncate(50*1024*1024+1)
        with patch.object(m,'SessionProvider',return_value=provider): self.assertFalse(json.loads(m.send(sid,'Lire',json.dumps([{'name':'x.pdf','mime':'application/pdf','path':str(pdf)}])))['ok'])
        self.assertTrue(pdf.exists())
    def test_task_duration_includes_tools_only_on_final_answer(self):
        for mode in ('work','code'):
            sid=self.new(mode); provider=Mock();provider.url=None
            action=json.dumps({'tool':'write_file','parameters':{'path':'a.txt','content':'oui'}})
            provider.send_message.side_effect=[{'success':True,'response':'Je travaille.\n```json\n'+action+'\n```'},{'success':True,'response':'Terminé.'}]
            orig=m._tool
            def delayed(*args): time.sleep(.12); return orig(*args)
            with patch.object(m,'SessionProvider',return_value=provider),patch.object(m,'_tool',side_effect=delayed): self.assertTrue(json.loads(m.send(sid,'Test'))['ok'])
            msgs=json.loads(m.get_session(sid))['messages']; assistants=[x for x in msgs if x['role']=='assistant']
            self.assertGreaterEqual(assistants[-1]['duration_ms'],100)
            self.assertTrue(all(x['duration_ms'] is None for x in assistants[:-1]))
if __name__=='__main__': unittest.main()
