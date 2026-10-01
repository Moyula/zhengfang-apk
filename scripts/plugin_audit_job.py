#!/usr/bin/env python3
"""Trusted GitHub Actions bootstrap. Submitted code never receives Actions secrets.
No submitted sources, reports or raw tool output are uploaded as Actions artifacts.
"""
import hashlib,json,os,pathlib,re,subprocess,sys,tempfile,zipfile,io
ORIGIN='https://plugins.hidisiwa.xyz'

def http(path,body=None,nonce=None):
    token=os.environ.get('AUDIT_QUEUE_TOKEN','')
    if not re.fullmatch(r'[a-f0-9]{64}',token):raise RuntimeError('QUEUE_IDENTITY_MISSING')
    config=['silent','show-error','fail-with-body','connect-timeout = 10','max-time = 30','max-filesize = 12582912','url = '+json.dumps(ORIGIN+path),'header = '+json.dumps('Authorization: Bearer '+token)]
    if nonce:config+=['header = '+json.dumps('X-Audit-Nonce: '+nonce)]
    if body is not None:config+=['request = "POST"','header = "Content-Type: application/json"','data = '+json.dumps(json.dumps(body,separators=(',',':')))]
    result=subprocess.run(['curl','--config','-'],input=('\n'.join(config)+'\n').encode(),capture_output=True,timeout=35)
    if result.returncode:raise RuntimeError('QUEUE_REQUEST_FAILED')
    return result.stdout

def extract_kit(data,target):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        members=archive.infolist()
        if len(members)>3000 or sum(i.file_size for i in members)>32*1024*1024:raise RuntimeError('TOOLCHAIN_SIZE')
        names=set()
        for member in members:
            parts=pathlib.PurePosixPath(member.filename)
            if parts.is_absolute() or '..' in parts.parts or member.filename in names or (member.external_attr>>16)&0o170000==0o120000:raise RuntimeError('TOOLCHAIN_PATH')
            names.add(member.filename)
        archive.extractall(target)

def task():
    draft=os.environ.get('AUDIT_DRAFT_ID','');nonce=os.environ.get('AUDIT_NONCE','')
    if not re.fullmatch(r'[A-Za-z0-9_-]{16}',draft) or not re.fullmatch(r'[A-Za-z0-9_-]{20,100}',nonce):raise RuntimeError('TASK_INPUT_INVALID')
    return draft,nonce

def main():
    self_test=os.environ.get('AUDIT_SELF_TEST')=='true'
    if '--failure' in sys.argv:
        draft,nonce=task();http('/api/audit-runner/jobs/'+draft+'/start-failure',{'nonce':nonce});return
    draft,nonce=('', '') if self_test else task()
    metadata=json.loads(http('/api/audit-runner/runtime'))
    if metadata.get('url')!=ORIGIN+'/downloads/plugin-starter-v3.zip' or not re.fullmatch(r'[a-f0-9]{64}',metadata.get('sha256','')):raise RuntimeError('TOOLCHAIN_METADATA')
    data=http('/downloads/plugin-starter-v3.zip?audit='+metadata['sha256'])
    if hashlib.sha256(data).hexdigest()!=metadata['sha256']:raise RuntimeError('TOOLCHAIN_DIGEST')
    clean={'PATH':os.environ['PATH'],'LANG':'C.UTF-8'}
    with tempfile.TemporaryDirectory(prefix='plugin-audit-',dir=os.environ.get('RUNNER_TEMP')) as tmp:
        work=pathlib.Path(tmp);kit=work/'kit';kit.mkdir();extract_kit(data,kit)
        # Only the fixed official toolchain lockfile is installed, with lifecycle scripts disabled.
        subprocess.run(['npm','ci','--ignore-scripts','--no-audit','--no-fund'],cwd=kit,env=clean,check=True,capture_output=True,timeout=120)
        key=work/'audit.pem';key.write_text(os.environ.get('AUDIT_SIGNING_KEY',''));key.chmod(0o600)
        if 'PRIVATE KEY' not in key.read_text():raise RuntimeError('SIGNING_IDENTITY_MISSING')
        public=subprocess.run(['node','--input-type=module','-e',"import {readFileSync} from 'node:fs';import {createPublicKey} from 'node:crypto';process.stdout.write(createPublicKey(readFileSync(process.argv[1])).export({type:'spki',format:'der'}).toString('base64'));",str(key)],env=clean,capture_output=True,check=True,timeout=10).stdout.decode()
        if public!=metadata.get('signingPublicKey'):raise RuntimeError('SIGNING_IDENTITY_MISMATCH')
        if self_test:
            result=subprocess.run(['node','--test','--test-concurrency=1','security/test-isolated.mjs'],cwd=kit,env=clean,capture_output=True,timeout=120)
            if result.returncode:raise RuntimeError('ISOLATED_SELF_TEST_FAILED')
            print('Four public synthetic isolation checks passed; no submission processed.')
            outcome='Public toolchain self-test passed (4 checks).'
        else:
            config=work/'config.json';config.write_text(json.dumps({'origin':ORIGIN,'token':os.environ['AUDIT_QUEUE_TOKEN'],'keyFile':str(key),'tempRoot':tmp,'processLock':str(work/'process.lock'),'heavyLock':str(work/'heavy.lock'),'draftId':draft,'nonce':nonce}));config.chmod(0o600)
            subprocess.run(['python3','security/agent.py','--config',str(config),'--once'],cwd=kit,env=clean,check=True,capture_output=True,timeout=210)
            state=json.loads(http('/api/audit-runner/jobs/'+draft+'/outcome',nonce=nonce))['state']
            if state not in ('passed','failed','cancelled'):raise RuntimeError('AUDIT_INCOMPLETE')
            outcome={'passed':'Plugin checks passed. Human publication approval is still required.','failed':'Plugin checks failed. See the private website review for details.','cancelled':'Task superseded or expired; no publication performed.'}[state]
            print(outcome)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as summary:summary.write(outcome+'\n\nSource, private reports, and signing keys are not uploaded to Actions artifacts.\n')
if __name__=='__main__':
    try:main()
    except Exception as error:
        # Never echo subprocess stderr or untrusted fixture data into public logs.
        message=str(error)
        print(message if re.fullmatch('[A-Z_]+',message) else 'AUDIT_EXECUTION_FAILED',file=sys.stderr)
        sys.exit(1)
