#!/usr/bin/env python3
"""Synchronize an existing signed release; never rebuild or overwrite another APK."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlencode

ROOT = 'https://gitee.com/api/v5/repos/znj12345/zhengfang'


class Gitee:
    def __init__(self, token):
        if not token:
            raise ValueError('GITEE_TOKEN is required')
        self.token = token

    def request(self, method, path, payload=None, upload=None, allow_missing=False):
        url = ROOT + '/' + path
        config = []
        if method == 'GET':
            url += ('&' if '?' in url else '?') + urlencode({'access_token': self.token})
        elif upload is not None:
            config += ['form = ' + json.dumps('access_token=' + self.token),
                       'form = ' + json.dumps('file=@' + str(upload.resolve()))]
        else:
            config += ['header = "Content-Type: application/json"',
                       'data-binary = ' + json.dumps(json.dumps(dict(payload or {}, access_token=self.token)))]
        config += ['url = ' + json.dumps(url)]
        with tempfile.TemporaryDirectory() as directory:
            response = Path(directory) / 'response.json'
            # Config through stdin keeps credentials out of process arguments and logs.
            result = subprocess.run([
                'curl', '--config', '-', '--ipv4', '--http1.1', '--silent', '--show-error',
                '--connect-timeout', '15', '--max-time', '240' if upload else '45',
                '--header', 'Expect:', '--request', method, '--output', str(response),
                '--write-out', '%{http_code}',
            ], input='\n'.join(config) + '\n', capture_output=True, text=True, timeout=260)
            status = result.stdout.strip()
            if result.returncode:
                raise RuntimeError(f'Gitee {method} {path} transport failed (curl {result.returncode})')
            if status == '404' and allow_missing:
                return None
            if not status.startswith('2'):
                raise RuntimeError(f'Gitee {method} {path} failed (HTTP {status})')
            return json.loads(response.read_text())


def verify_apk(tag, apk):
    url = f'https://gitee.com/znj12345/zhengfang/releases/download/{tag}/app-release.apk'
    with tempfile.TemporaryDirectory() as directory:
        target = Path(directory) / 'download.apk'
        subprocess.run(['curl', '--ipv4', '--http1.1', '--fail', '--silent', '--show-error',
                        '--location', '--connect-timeout', '15', '--max-time', '180',
                        url, '--output', str(target)], check=True, timeout=200)
        if hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(apk.read_bytes()).digest():
            raise RuntimeError('Existing Gitee APK differs from the signed source; refusing metadata update')
    return url


def sync_release(client, tag, apk, notes):
    if re.fullmatch(r'v1\.0\.\d+', tag) is None or not notes.strip() or not apk.is_file():
        raise ValueError('A valid tag, APK and nonempty release notes are required')
    code = int(tag.rsplit('.', 1)[1])
    metadata = client.request('GET', 'contents/version.json?ref=main', allow_missing=True)
    if metadata:
        current = json.loads(base64.b64decode(metadata['content']).decode('utf-8-sig'))
        if int(current['versionCode']) > code:
            raise ValueError('Refusing to replace a newer published version')
    release = client.request('GET', 'releases/tags/' + tag, allow_missing=True)
    if release is None:
        release = client.request('POST', 'releases', {
            'tag_name': tag, 'target_commitish': 'main', 'name': 'Release ' + tag,
            'body': notes, 'prerelease': False,
        })
    release_id = int(release['id'])
    assets_path = f'releases/{release_id}/attach_files'
    assets = client.request('GET', assets_path)
    if not any(a.get('name') == 'app-release.apk' for a in assets):
        print('Uploading the existing signed APK to Gitee (bounded to 240 seconds)', flush=True)
        try:
            client.request('POST', assets_path, upload=apk)
        except RuntimeError:
            # A lost response may follow successful server storage. Check before another write.
            assets = client.request('GET', assets_path)
            if not any(a.get('name') == 'app-release.apk' for a in assets):
                raise
    url = verify_apk(tag, apk)
    version = dict(versionCode=code, versionName=tag[1:], releaseNotes=notes,
                   downloadUrl=url, forceUpdate=True)
    payload = dict(message='release: ' + tag, branch='main',
                   content=base64.b64encode(json.dumps(version, ensure_ascii=False).encode()).decode())
    if metadata:
        payload['sha'] = metadata['sha']
    client.request('PUT' if metadata else 'POST', 'contents/version.json', payload)
    print('Gitee APK digest verified; update metadata synchronized', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--notes-file', type=Path, required=True)
    args = parser.parse_args()
    sync_release(Gitee(os.environ.get('GITEE_TOKEN', '')), args.tag,
                 args.apk, args.notes_file.read_text().strip())


if __name__ == '__main__':
    main()
