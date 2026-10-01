from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap_stable_channel as b
from release_distribution import Budget, DeliveryError


class StableBootstrapTests(unittest.TestCase):
    def test_existing_artifact_reused_without_announcements_or_legacy_changes(self):
        with tempfile.TemporaryDirectory() as root, patch.object(b, 'existing_release', return_value=(Path(root)/'apk', {}, 'notes')), patch.object(b, 'deploy_release', return_value={'signed': True}) as deploy, patch.object(b, 'MetadataClient'), patch.object(b, 'publish_metadata', return_value={'versionCode': 97, 'sha256': 'a'*64}) as publish:
            result = b.repair('v1.0.97', root, Budget())
        self.assertFalse(deploy.call_args.kwargs['publish_announcements'])
        self.assertTrue(publish.call_args.kwargs['signed_only'])
        self.assertFalse(result['legacyPromptChanged'])
        self.assertFalse(result['apkRebuilt'])
        self.assertFalse(result['formalReleaseCreated'])

    def test_invalid_existing_artifact_does_not_publish(self):
        with tempfile.TemporaryDirectory() as root, patch.object(b, 'existing_release', side_effect=DeliveryError('invalid')), patch.object(b, 'deploy_release') as deploy:
            with self.assertRaises(DeliveryError): b.repair('v1.0.97', root, Budget())
        deploy.assert_not_called()
