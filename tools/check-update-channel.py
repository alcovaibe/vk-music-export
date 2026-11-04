"""Read-only validation of the channel used by installed apps against GitHub Latest.

No branch, PR, release or acceptance is changed. --verify-apk checks downloaded
bytes, cryptographic signature and actual APK manifest; manual acceptance is separate.
"""
import argparse
import json
import time
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
    parser.add_argument('--public-retries', type=int, default=1, choices=range(1, 7), help='Retry public CDN propagation up to six times, ten seconds apart')
    parser.add_argument('--output', type=Path, default=Path('channel-evidence'))
    args = parser.parse_args()
    if args.public_retries != 1 and args.manifest:
        parser.error('--public-retries is only for the live public channel')
    (args.output/'channel-verification.json').unlink(missing_ok=True)
    if args.schema_only and args.manifest is None:
        parser.error('--schema-only requires --manifest')
    if args.manifest:
        manifest = json.loads(args.manifest.read_text())
    else:
        # The app reads this same main path. Fetch it afresh, independently of the
        # checkout used by a scheduled job or manually selected workflow branch.
        for attempt in range(args.public_retries):
            try:
                manifest = public_manifest()
                check_latest(manifest, api(f'repos/{REPO}/releases/latest'))
                break
            except ValueError:
                if attempt+1 == args.public_retries:
                    raise
                time.sleep(10)
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
        # Do not attest delivery if Latest or the raw pointer moved during verification.
        final_latest = api(f'repos/{REPO}/releases/latest')
        check_latest(manifest, final_latest)
        if (final_latest['id'], final_latest['target_commitish']) != (latest['id'], latest['target_commitish']):
            raise ValueError('Latest changed during channel verification')
        if args.manifest is None and public_manifest() != manifest:
            raise ValueError('Public channel changed during verification')
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output/'channel-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        (args.output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
        print(f"PASS: {tag}; {result['state']}; APK signature verified={args.verify_apk} (manual acceptance is separate).")


if __name__ == '__main__':
    main()
