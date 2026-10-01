#!/usr/bin/env python3
"""Explicit one-time test rollout for legacy clients; normal promotion stays independent."""
import argparse
import json
import os
from pathlib import Path
import tempfile
from release_distribution import Budget, DeliveryError, verify_manifest, fetch, inspect_apk
from publish_update_metadata import MetadataClient, write_json

def bridge(client,envelope,receipt,budget):
    payload=verify_manifest(envelope,'test')
    if payload['versionCode']!=98 or payload['versionName']!='1.0.98':
        raise DeliveryError('This one-time legacy test rollout is restricted to 1.0.98')
    for key in ['packageName','versionCode','versionName','sha256','size','sourceSha','buildId']:
        if payload[key]!=receipt[key]: raise DeliveryError('Test manifest does not match original verified APK')
    url=None
    with tempfile.TemporaryDirectory() as root:
        for mirror in payload['mirrors']:
            try:
                fetch(mirror['url'],Path(root)/'public.apk',budget,expected=receipt)
                url=mirror['url'];break
            except DeliveryError: continue
    if url is None: raise DeliveryError('No verified public test APK; legacy metadata unchanged')
    metadata,current=client.read_json_file('version.json',allow_missing=True)
    if current:
        if current.get('versionCode',0)>payload['versionCode']:
            raise DeliveryError('Refusing older legacy rollout')
        if current.get('versionCode')==payload['versionCode'] and current.get('sha256') not in (None,payload['sha256']):
            raise DeliveryError('Refusing same-version APK replacement')
    result={key:payload[key] for key in ['versionCode','versionName','sha256','size']}
    result.update(downloadUrl=url,forceUpdate=False,releaseChannel='test',
        releaseNotes='本次为 1.0.98 迁移测试更新，支持国内备用下载与断点恢复。可选择稍后更新。\n\n'+payload['releaseNotes'])
    if current!=result:
        write_json(client,'version.json',result,metadata)
    _,stored=client.read_json_file('version.json')
    if stored!=result: raise DeliveryError('Legacy rollout verification failed')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--directory',type=Path,default=Path('delivery'));args=parser.parse_args()
    budget=Budget(170);directory=args.directory;report=dict(result='incomplete',formalReleasePublished=False)
    try:
        receipt=json.loads((directory/'receipt.json').read_text());actual=inspect_apk(directory/'app-release.apk',budget)
        if any(receipt.get(k)!=v for k,v in actual.items()): raise DeliveryError('APK receipt mismatch')
        manifest=directory/'bridge-test.json';fetch('https://dl-test.hidisiwa.xyz/test.json',manifest,budget)
        legacy=bridge(MetadataClient(os.environ.get('GITEE_TOKEN',''),budget),json.loads(manifest.read_text()),receipt,budget)
        report.update(result='verified',legacy=legacy)
    except Exception as e:
        report.update(result='failed',reason=str(e) if isinstance(e,(DeliveryError,RuntimeError)) else type(e).__name__)
        raise SystemExit('Legacy test bridge failed: '+report['reason']) from None
    finally:
        report['events']=budget.events
        (directory/'legacy-test-bridge.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
