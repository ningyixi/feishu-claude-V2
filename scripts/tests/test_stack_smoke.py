"""Real HTTP checks through Nginx. Creates and removes only uniquely named fixtures."""
import json
import re
import urllib.request
import urllib.error
import uuid
import unittest
from scripts import database as db

class StackSmoke(unittest.TestCase):
    def test_modes_scope_and_preflight_through_frontend_proxy(self):
        base='http://127.0.0.1:'+db.config().get('FEISHU_FRONTEND_PORT','18080')
        def call(path,method='GET',body=None,status=200):
            request=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers={'Content-Type':'application/json'})
            try:
                with urllib.request.urlopen(request) as response: actual=response.status;result=json.load(response)
            except urllib.error.HTTPError as error: actual=error.code;result=json.load(error)
            self.assertEqual(actual,status,(path,result));return result
        with urllib.request.urlopen(base+'/supplier') as response: html=response.read().decode()
        bundle=re.search(r'src="([^"]+\.js)"',html).group(1)
        with urllib.request.urlopen(base+bundle) as response: javascript=response.read().decode()
        self.assertIn('持续工作流',javascript)
        self.assertIn('同意并继续',javascript)
        code='wiki_smoke_'+uuid.uuid4().hex[:12];jobs=[];entry=None
        template=next(t for t in call('/api/workflows') if t['mode']=='API')
        try:
            for mode in ['TEXT','WIKI']:
                job=call('/api/supplier-jobs','POST',{'name':mode+' fixture','supplierCode':code,'supplierName':'fixture','libraryPath':'/workspace','templateId':template['id'],'knowledgeMode':mode})
                jobs.append(job['id']);self.assertEqual(job['knowledgeMode'],mode)
            call('/api/supplier-jobs','POST',{'supplierName':'bad mode','templateId':template['id'],'knowledgeMode':'RAG'},400)
            wiki=jobs[1];call(f'/api/supplier-jobs/{wiki}','PUT',{'knowledgeMode':'TEXT'},400)
            patched=call(f'/api/supplier-jobs/{wiki}','PUT',{'name':'renamed fixture'})
            self.assertEqual(patched['supplierCode'],code);self.assertEqual(patched['libraryPath'],'/workspace');self.assertEqual(patched['knowledgeMode'],'WIKI')
            self.assertEqual(call(f'/api/supplier-jobs/{wiki}/wiki')['status'],'EMPTY')
            self.assertEqual(call(f'/api/supplier-jobs/{wiki}/workflow')['status'],'NOT_STARTED')
            call(f'/api/supplier-jobs/{wiki}/workflow/start','POST',{'notifyFeishu':True},400)
            self.assertEqual(call(f'/api/supplier-jobs/{wiki}/workflow')['status'],'NOT_STARTED')
            call(f'/api/supplier-jobs/{wiki}/wiki/schema','PUT',{'instructions':'接口和商务约定分别组织，保留原文证据。'})
            self.assertTrue(call(f'/api/supplier-jobs/{wiki}/wiki/schema')['instructions'])
            call(f'/api/supplier-jobs/{jobs[0]}/wiki/schema','PUT',{'instructions':'not allowed'},400)
            entry=call('/api/supplier-kb/'+code+'/entries','POST',{'type':'chat','title':'fixture','value':'原始业务记录😀。'*3000+'取消必须传供应商订单号。'})['id']
            self.assertIn('取消必须传供应商订单号',call(f'/api/supplier-kb/entries/{entry}/md')['content'])
            self.assertIn('绑定',call(f'/api/supplier-jobs/{wiki}/wiki/build','POST',{},400)['error'])
            call(f'/api/supplier-jobs/{jobs[0]}/wiki/build','POST',{},400)
            self.assertEqual(call(f'/api/supplier-jobs/{wiki}/knowledge-runs'),[])
        finally:
            if entry:call(f'/api/supplier-kb/entries/{entry}','DELETE')
            for job in jobs:call(f'/api/supplier-jobs/{job}','DELETE')
            # Remove only this test's known source directories; all other supplier data remains untouched.
            cleanup="import pathlib,hashlib,sys; code=sys.argv[1]; root=pathlib.Path('/app/data/supplier-kb'); p=root/'.wiki'/hashlib.sha256(code.encode()).hexdigest()[:24]; (p/'instructions.md').unlink(missing_ok=True); p.rmdir(); q=root/code; q.rmdir() if q.exists() else None"
            db.compose('exec','-T','backend','python3','-c',cleanup,code)

if __name__=='__main__':unittest.main()
