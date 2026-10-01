#!/usr/bin/env python3
"""Repair the default channel from an existing official APK, preserving legacy rollout."""
import argparse
import json
import os
from pathlib import Path
from release_distribution import Budget, DeliveryError, deploy_release
from repair_delivery import existing_release
from publish_update_metadata import MetadataClient, publish_metadata


def repair(tag, directory, budget):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    apk, receipt, notes = existing_release(tag, directory, budget)
    envelope = deploy_release(apk, receipt, 'stable', notes, directory/'stable-mirror.json',
                              budget, publish_announcements=False)
    payload = publish_metadata(MetadataClient(os.environ.get('GITEE_TOKEN', ''), budget),
                               envelope, budget, signed_only=True)
    return dict(result='verified', versionCode=payload['versionCode'], sha256=payload['sha256'],
                legacyPromptChanged=False, apkRebuilt=False, formalReleaseCreated=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--directory', type=Path, default=Path('repair'))
    args = parser.parse_args()
    budget = Budget(420)
    report = dict(result='incomplete')
    try:
        report = repair(args.tag, args.directory, budget)
    except Exception as e:
        report.update(result='failed', reason=str(e) if isinstance(e, (DeliveryError, RuntimeError)) else type(e).__name__)
        raise SystemExit('Default update channel repair failed: '+report['reason']) from None
    finally:
        args.directory.mkdir(parents=True, exist_ok=True)
        report['events'] = budget.events
        (args.directory/'stable-bootstrap.json').write_text(json.dumps(report, indent=2)+'\n')
