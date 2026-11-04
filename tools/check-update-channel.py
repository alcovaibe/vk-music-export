"""Read-only validation of the channel used by installed apps against GitHub Latest.

No branch, PR, release or acceptance is changed. A matching asset digest is a
metadata consistency check, not a new download/signature/manual-acceptance test.
"""
import argparse
import base64
import json
from pathlib import Path

from update_channel import api, REPO, CHANNEL_PATH, PACKAGE, release_code
from channel_contract import validate_manifest
from channel_public import public_manifest, download_apk, verify_apk


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
    parser.add_argument('--verify-apk', action='store_true', help='Download public bytes and verify signature and actual APK identity')
    parser.add_argument('--output', type=Path, default=Path('channel-evidence'))
    args = parser.parse_args()
    if args.schema_only and args.manifest is None:
        parser.error('--schema-only requires --manifest')
    if args.manifest:
        manifest = json.loads(args.manifest.read_text())
    else:
        # The app reads this same main path. Fetch it afresh, independently of the
        # checkout used by a scheduled job or manually selected workflow branch.
        manifest = public_manifest()
    if args.schema_only:
        validate_manifest(manifest)
        print('PASS: local channel schema (no network/APK verification).')
    else:
        latest = api(f'repos/{REPO}/releases/latest')
        tag = check_latest(manifest, latest)
        result = {'schema': 1, 'state': 'candidate_verified' if args.manifest else 'public_channel_verified',
                  'manifest': manifest, 'release_id': latest['id'], 'release_commit': latest['target_commitish']}
        if args.verify_apk:
            apk = download_apk(manifest, args.output)
            result['apk_verification'] = verify_apk(apk, manifest)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output/'channel-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        (args.output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
        print(f"PASS: {tag}; {result['state']}; APK signature verified={args.verify_apk} (manual acceptance is separate).")


if __name__ == '__main__':
    main()
