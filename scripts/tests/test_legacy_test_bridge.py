import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import bridge_legacy_test as b
from test_update_metadata import Store
from test_release_distribution import payload
from release_distribution import Budget,DeliveryError

class BridgeTests(unittest.TestCase):
    def setUp(self):self.p=payload();self.store=Store()
    def run_bridge(self,receipt=None):
        with patch.object(b,'verify_manifest',return_value=self.p),patch.object(b,'fetch'):
            return b.bridge(self.store,{},receipt or self.p,Budget())
    def test_old_clients_receive_optional_test_update_without_attachment(self):
        result=self.run_bridge();self.assertEqual(result['versionCode'],98);self.assertFalse(result['forceUpdate'])
        self.assertIn('测试更新',result['releaseNotes']);self.assertEqual(result['releaseChannel'],'test')
        self.assertEqual([name for _,name,_ in self.store.writes],['version.json'])
    def test_inaccessible_public_apk_prevents_prompt(self):
        with patch.object(b,'verify_manifest',return_value=self.p),patch.object(b,'fetch',side_effect=DeliveryError('timeout')):
            with self.assertRaises(DeliveryError):b.bridge(self.store,{},self.p,Budget())
        self.assertFalse(self.store.writes)
    def test_receipt_mismatch_prevents_prompt(self):
        with self.assertRaises(DeliveryError):self.run_bridge(dict(self.p,sha256='c'*64))
        self.assertFalse(self.store.writes)
    def test_newer_version_never_overwritten(self):
        self.store.files['version.json']={'versionCode':99}
        with self.assertRaises(DeliveryError):self.run_bridge()
        self.assertFalse(self.store.writes)
    def test_duplicate_run_is_idempotent(self):
        self.run_bridge();self.store.writes.clear();self.run_bridge();self.assertFalse(self.store.writes)
    def test_conflicting_same_version_is_rejected(self):
        self.store.files['version.json']={'versionCode':98,'sha256':'c'*64}
        with self.assertRaises(DeliveryError):self.run_bridge()
        self.assertFalse(self.store.writes)

if __name__=='__main__':unittest.main()
