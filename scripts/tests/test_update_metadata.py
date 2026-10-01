import base64
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_distribution as d
import publish_update_metadata as m
from publish_announcement import validate_announcement
from test_release_distribution import payload
from test_sync_gitee_release import FakeGitee
from sync_gitee_release import sync_release

class Store:
    def __init__(self):
        self.files={'version.json':{'versionCode':97},'announcement.json':{'announcements':[{'id':'old','title':'旧','content':'旧'}]}}
        self.writes=[]
    def read_json_file(self,name,allow_missing=False):
        return ({'sha':'original-'+name},copy.deepcopy(self.files[name])) if name in self.files else (None,None)
    def request(self,method,path,body=None,**kwargs):
        if not path.startswith('contents/'): raise AssertionError('Must not access Gitee releases or attachments')
        name=path[9:]; self.writes.append((method,name,body)); self.files[name]=json.loads(base64.b64decode(body['content']))
        return {}

class MetadataTests(unittest.TestCase):
    def setUp(self):
        self.p=payload('stable'); self.store=Store()
        self.release={'draft':False,'prerelease':False,'assets':[{'name':'app-release.apk','size':3,'digest':'sha256:'+'a'*64}]}
    def publish(self):
        with patch.object(m,'verify_manifest',return_value=self.p), patch.object(d,'verify_manifest',return_value=self.p), patch.object(m,'gh_api',return_value=self.release), patch.object(m,'fetch'):
            return m.publish_metadata(self.store,{'signed':True},d.Budget())
    def test_external_mirror_metadata_needs_no_gitee_attachment(self):
        result=self.publish()
        self.assertEqual(result['downloadUrl'],self.p['mirrors'][0]['url'])
        self.assertFalse(result['forceUpdate'])
        self.assertEqual(self.store.files['announcement.json']['announcements'][1]['id'],'old')
    def test_announcement_id_matches_version_and_is_repeatable(self):
        a=d.release_announcement(self.p); validate_announcement(a,'v1.0.98')
        self.assertEqual(a['id'],d.release_announcement(self.p)['id'])
    def test_repeated_publication_does_not_duplicate_history(self):
        self.publish(); self.publish()
        self.assertEqual(len(self.store.files['announcement.json']['announcements']),2)
    def test_missing_domestic_candidate_keeps_previous_metadata(self):
        with patch.object(m,'verify_manifest',return_value=self.p), patch.object(m,'gh_api',return_value=self.release), patch.object(m,'fetch',side_effect=d.DeliveryError('timeout')):
            with self.assertRaises(d.DeliveryError): m.publish_metadata(self.store,{},d.Budget())
        self.assertFalse(self.store.writes)
    def test_newer_legacy_metadata_not_overwritten(self):
        self.store.files['version.json']={'versionCode':99}
        with self.assertRaises(d.DeliveryError): self.publish()
        self.assertFalse(self.store.writes)
    def test_conflicting_official_apk_not_published(self):
        self.release['assets'][0]['digest']='sha256:'+'c'*64
        with self.assertRaises(d.DeliveryError): self.publish()
        self.assertFalse(self.store.writes)
    def test_explicit_missing_file_is_not_reread(self):
        with patch.object(self.store,'read_json_file',side_effect=AssertionError('unexpected reread')):
            m.write_json(self.store,'version.json',{},None)
        self.assertEqual(self.store.writes[0][0],'POST')
    def test_attachment_only_does_not_read_or_write_metadata(self):
        client=FakeGitee(assets=True,code=999)
        with tempfile.TemporaryDirectory() as root, patch('sync_gitee_release.verify_apk',return_value='verified'):
            apk=Path(root)/'apk';apk.write_bytes(b'apk')
            sync_release(client,'v1.0.97',apk,'notes',attachments_only=True)
        self.assertFalse(any('contents/' in path for _,path in client.calls))
    def test_announcement_history_fetch_failure_never_makes_empty_history(self):
        with patch.object(d,'fetch',side_effect=d.DeliveryError('timeout')):
            with self.assertRaises(d.DeliveryError): d.merged_announcements(self.p,d.Budget())

    def test_diverged_announcement_sources_preserve_both_histories(self):
        def fetch(url,target,*args,**kwargs):
            ident='github-newer' if 'githubusercontent' in url else 'gitee-older'
            Path(target).write_text(json.dumps({'announcements':[{'id':ident,'title':'old','content':'old'}]}))
        with patch.object(d,'fetch',side_effect=fetch):
            merged=d.merged_announcements(self.p,d.Budget())
        self.assertEqual({a['id'] for a in merged['announcements']},{d.release_announcement(self.p)['id'],'github-newer','gitee-older'})
    def test_signed_metadata_write_uses_the_same_snapshot_as_rollback_check(self):
        with patch.object(self.store,'read_json_file',wraps=self.store.read_json_file) as read:
            self.publish()
        self.assertEqual(sum(call.args[0]=='app-update-stable.json' for call in read.call_args_list),1)

if __name__ == '__main__': unittest.main()
