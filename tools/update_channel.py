"""Advertise only a published, byte-verified Latest APK through the public JSON channel."""
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

REPO = 'alcovaibe/vk-music-export'
CHANNEL_PATH = 'docs/updates/android.json'
PACKAGE = 'app.alcovaibe.vkmusicexport'


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def release_code(tag):
    match = re.fullmatch(r'v[0-9]{1,6}(?:\.[0-9]{1,6}){1,3}-build\.([1-9][0-9]{0,9})', tag)
    assert match and 0 < int(match[1]) <= 2_100_000_000, 'Invalid release tag'
    return int(match[1])


def channel_manifest(info, release, apk_path):
    assert not release['draft'] and not release['prerelease']
    assert release['tag_name'] == info['tag']
    assert info['applicationId'] == PACKAGE
    assert release_code(info['tag']) == info['versionCode']
    assert info['tag'] == f"v{info['versionName']}-build.{info['versionCode']}"
    expected = f"VK-Music-Export-{info['versionName']}-{info['versionCode']}.apk"
    assert info['apk'] == expected
    assets = [a for a in release['assets'] if a['name'] == expected]
    assert len(assets) == 1
    asset = assets[0]
    size = apk_path.stat().st_size
    digest = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    assert asset['state'] == 'uploaded' and asset['size'] == size and 0 < size <= 100 * 1024 * 1024
    assert asset['digest'] == 'sha256:' + digest, 'Published asset differs from the checked APK'
    assert isinstance(info['minSdk'], int) and 1 <= info['minSdk'] <= 10000
    return {'schemaVersion': 1, 'platform': 'android', 'channel': 'stable', 'applicationId': PACKAGE,
            'release': {'versionName': info['versionName'], 'versionCode': info['versionCode'],
                        'minSdk': info['minSdk'], 'tag': info['tag'], 'apk': expected,
                        'size': size, 'sha256': digest}}


def publish_channel(info, apk_path):
    latest = api(f'repos/{REPO}/releases/latest')
    # An old recovery or rerun must never roll back the public channel.
    if latest['tag_name'] != info['tag']:
        assert release_code(latest['tag_name']) >= info['versionCode']
        print('A newer Latest exists; public channel was not changed.')
        return
    manifest = channel_manifest(info, latest, apk_path)
    existing = api(f'repos/{REPO}/contents/{CHANNEL_PATH}?ref=main')
    old = json.loads(base64.b64decode(existing['content']))
    assert old['applicationId'] == PACKAGE and old['schemaVersion'] == 1
    old_code = old['release']['versionCode']
    if old_code >= info['versionCode']:
        assert old_code > info['versionCode'] or old == manifest, 'Same version has different channel metadata'
        return
    payload = {'message': f"docs: advertise {info['tag']} in public update channel [skip ci]",
               'branch': 'main', 'sha': existing['sha'],
               'content': base64.b64encode((json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()).decode()}
    # The contents API compares the file SHA; concurrent changes are rejected.
    subprocess.run(['gh', 'api', '--method', 'PUT', f'repos/{REPO}/contents/{CHANNEL_PATH}', '--input', '-'],
                   input=json.dumps(payload), text=True, check=True)
