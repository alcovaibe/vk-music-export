"""Read-only validation of the channel used by installed apps against GitHub Latest.

No branch, PR, release or acceptance is changed. A matching asset digest is a
metadata consistency check, not a new download/signature/manual-acceptance test.
"""
import argparse
import base64
import json
from pathlib import Path
import re

from update_channel import api, REPO, CHANNEL_PATH, PACKAGE, release_code


def validate_manifest(manifest):
    if (type(manifest.get('schemaVersion')) is not int or manifest.get('schemaVersion') != 1 or manifest.get('platform') != 'android'
            or manifest.get('channel') != 'stable' or manifest.get('applicationId') != PACKAGE):
        raise ValueError('Unsupported stable channel schema/package')
    item = manifest['release']
    version = item['versionName']
    if not isinstance(version, str) or not re.fullmatch(r'[0-9]{1,6}(?:\.[0-9]{1,6}){1,3}', version):
        raise ValueError('Invalid versionName')
    for key, maximum in (('versionCode', 2_100_000_000), ('minSdk', 10000), ('size', 100 * 1024 * 1024)):
        if type(item[key]) is not int or not 0 < item[key] <= maximum:
            raise ValueError(f'Invalid {key}')
    if (item['tag'] != f"v{version}-build.{item['versionCode']}"
            or item['apk'] != f"VK-Music-Export-{version}-{item['versionCode']}.apk"
            or not re.fullmatch(r'[0-9a-f]{64}', item['sha256'])):
        raise ValueError('Invalid tag, APK name or SHA-256')
    return item


def check_latest(manifest, latest):
    item = validate_manifest(manifest)
    if latest['draft'] or latest['prerelease']:
        raise ValueError('Latest is not a published stable release')
    if latest['tag_name'] != item['tag']:
        raise ValueError(f"Channel {item['tag']} differs from Latest {latest['tag_name']}; review the channel PR")
    if release_code(latest['tag_name']) != item['versionCode']:
        raise ValueError('Latest versionCode differs from channel')
    assets = [asset for asset in latest['assets'] if asset['name'] == item['apk']]
    if len(assets) != 1:
        raise ValueError('Expected exactly one Latest APK asset')
    asset = assets[0]
    if (asset['state'] != 'uploaded' or asset['size'] != item['size']
            or asset.get('digest') != 'sha256:' + item['sha256']):
        raise ValueError('Latest APK asset size/digest differs from channel')
    return item['tag']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, help='Validate a local/PR manifest instead of the live main channel')
    parser.add_argument('--schema-only', action='store_true', help='Offline schema check; requires --manifest')
    args = parser.parse_args()
    if args.schema_only and args.manifest is None:
        parser.error('--schema-only requires --manifest')
    if args.manifest:
        manifest = json.loads(args.manifest.read_text())
    else:
        # The app reads this same main path. Fetch it afresh, independently of the
        # checkout used by a scheduled job or manually selected workflow branch.
        content = api(f'repos/{REPO}/contents/{CHANNEL_PATH}?ref=main')
        manifest = json.loads(base64.b64decode(content['content'], validate=False))
    if args.schema_only:
        validate_manifest(manifest)
        print('PASS: local channel schema (no network/APK verification).')
    else:
        tag = check_latest(manifest, api(f'repos/{REPO}/releases/latest'))
        print(f'PASS: {tag}; public channel and Latest asset metadata agree (not a new APK acceptance test).')


if __name__ == '__main__':
    main()
