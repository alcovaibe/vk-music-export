"""Prepare/resume a byte-verified channel PR. Never merge main or call a PR delivered."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from channel_contract import REPO, CHANNEL_PATH, PACKAGE, release_code, validate_manifest, file_hash
from channel_public import verify_apk


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def write_api(path, method, payload):
    result = subprocess.run(['gh', 'api', '--method', method, path, '--input', '-'],
                            input=json.dumps(payload), text=True, capture_output=True, check=True)
    return json.loads(result.stdout) if result.stdout.strip() else None


def channel_manifest(info, release, apk_path):
    if release['draft'] or release['prerelease'] or release['tag_name'] != info['tag'] or info['applicationId'] != PACKAGE:
        raise ValueError('Expected the exact published stable release/package')
    if type(info['versionCode']) is not int or release_code(info['tag']) != info['versionCode']:
        raise ValueError('Invalid build versionCode')
    if info['tag'] != f"v{info['versionName']}-build.{info['versionCode']}":
        raise ValueError('Release tag differs from build identity')
    expected = f"VK-Music-Export-{info['versionName']}-{info['versionCode']}.apk"
    if info['apk'] != expected:
        raise ValueError('APK name differs from build identity')
    assets = [a for a in release['assets'] if a['name'] == expected]
    if len(assets) != 1:
        raise ValueError('Missing or ambiguous release APK')
    asset = assets[0]
    size = apk_path.stat().st_size
    digest = file_hash(apk_path)
    if asset['state'] != 'uploaded' or type(asset['size']) is not int or asset['size'] != size:
        raise ValueError('APK upload is incomplete or has another size')
    if asset['digest'] != 'sha256:' + digest:
        raise ValueError('Published asset differs from the checked APK')
    if type(info['minSdk']) is not int or not 1 <= info['minSdk'] <= 10000:
        raise ValueError('Invalid minimum SDK')
    manifest = {'schemaVersion': 1, 'platform': 'android', 'channel': 'stable', 'applicationId': PACKAGE,
                'release': {'versionName': info['versionName'], 'versionCode': info['versionCode'],
                            'minSdk': info['minSdk'], 'tag': info['tag'], 'apk': expected,
                            'size': size, 'sha256': digest}}
    validate_manifest(manifest)
    return manifest


def channel_at(ref):
    item = api(f'repos/{REPO}/contents/{CHANNEL_PATH}?ref={ref}')
    raw = base64.b64decode(''.join(item['content'].split()), validate=True)
    value = json.loads(raw.decode('utf-8', errors='strict'))
    validate_manifest(value)
    return item, value


def branch_head(branch):
    rows = api(f'repos/{REPO}/git/matching-refs/heads/{branch}')
    exact = [r for r in rows if r['ref'] == 'refs/heads/' + branch]
    if len(exact) > 1:
        raise ValueError('Ambiguous channel branch')
    return exact[0]['object']['sha'] if exact else None


def validate_branch(base, head, manifest):
    comparison = api(f'repos/{REPO}/compare/{base}...{head}')
    files = comparison.get('files', [])
    if len(files) != 1 or files[0]['filename'] != CHANNEL_PATH or files[0]['status'] != 'modified':
        raise ValueError('Channel branch contains unrelated or missing changes')
    _, actual = channel_at(head)
    if actual != manifest:
        raise ValueError('Channel branch advertises different immutable metadata')


def pending_review(branch, head):
    # A token-created PR may await approval or not start its checks. Dispatch the
    # existing Android workflow explicitly, bound to the inspected head.
    subprocess.run(['gh', 'workflow', 'run', 'android.yml', '--repo', REPO, '--ref', branch,
                    '-f', 'channel=checks', '-f', 'expected_sha=' + head], check=True)


def _prepare_channel(info, apk_path, output):
    output.mkdir(parents=True, exist_ok=True)
    manifest = None
    def finish(state, **details):
        record = {'schema': 1, 'state': state, 'tag': info['tag'], 'versionCode': info['versionCode'], **details}
        (output/'channel-delivery.json').write_text(json.dumps(record, ensure_ascii=False, indent=2)+'\n')
        message = f"Update channel: {state}; main was not changed by delivery automation."
        print(message)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write('\n'+message+'\n'+json.dumps(details, ensure_ascii=False)+'\n')
        return record
    latest = api(f'repos/{REPO}/releases/latest')
    if latest['tag_name'] != info['tag']:
        if release_code(latest['tag_name']) <= info['versionCode']:
            raise ValueError('Latest is inconsistent with candidate version')
        return finish('superseded', latest=latest['tag_name'])
    manifest = channel_manifest(info, latest, apk_path)
    verify_apk(apk_path, manifest)
    (output/'public-update-channel.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    base = api(f'repos/{REPO}/git/ref/heads/main')['object']['sha']
    _, old = channel_at(base)
    if old['release']['versionCode'] > info['versionCode']:
        raise ValueError('Main channel is ahead of Latest; refusing rollback')
    if old['release']['versionCode'] == info['versionCode']:
        if old != manifest:
            raise ValueError('Same version has different channel metadata')
        return finish('committed_waiting_public_verification', main_sha=base)
    branch = f"release/update-channel-{info['versionCode']}"
    prs = api(f'repos/{REPO}/pulls?head=alcovaibe:{branch}&state=all&per_page=100')
    opened = [p for p in prs if p['state'] == 'open']
    if len(opened) > 1 or prs and not opened:
        raise ValueError('Channel PR was closed or is ambiguous; inspect it before retrying')
    head = branch_head(branch)
    if head is None:
        write_api(f'repos/{REPO}/git/refs', 'POST', {'ref': 'refs/heads/'+branch, 'sha': base})
        head = base
    branch_file, value = channel_at(head)
    if value != manifest:
        if head != base:
            relation = api(f'repos/{REPO}/compare/{head}...{base}')
            if relation['status'] != 'ahead' or relation['behind_by'] != 0:
                raise ValueError('Existing branch has unreviewed content; refusing overwrite')
            write_api(f'repos/{REPO}/git/refs/heads/{branch}', 'PATCH', {'sha': base, 'force': False})
            head = base
            branch_file, value = channel_at(head)
        write_api(f'repos/{REPO}/contents/{CHANNEL_PATH}', 'PUT', {
            'message': f"docs: advertise {info['tag']} in public update channel", 'branch': branch,
            'sha': branch_file['sha'], 'content': base64.b64encode((json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode()).decode()})
        head = branch_head(branch)
    validate_branch(base, head, manifest)
    if api(f'repos/{REPO}/releases/latest')['tag_name'] != info['tag']:
        return finish('superseded', branch=branch, head_sha=head)
    prs = api(f'repos/{REPO}/pulls?head=alcovaibe:{branch}&state=all&per_page=100')
    opened = [p for p in prs if p['state'] == 'open']
    if len(opened) > 1 or prs and not opened:
        raise ValueError('Channel PR was closed or is ambiguous; inspect it before retrying')
    if not opened:
        with tempfile.TemporaryDirectory() as directory:
            body = Path(directory)/'body.md'
            body.write_text(f"Advertise published stable {info['tag']}. Exact APK bytes, signing certificate and actual manifest were verified.\n\n"
                            f"Checked head: `{head}`. Only `{CHANNEL_PATH}` may change.\n\n"
                            'This is pending delivery. Merge requires owner approval; public raw JSON must be verified afterwards.\n')
            subprocess.run(['gh', 'pr', 'create', '--repo', REPO, '--base', 'main', '--head', branch,
                            '--title', f"Update stable channel to {info['tag']}", '--body-file', str(body)], check=True)
        opened = [p for p in api(f'repos/{REPO}/pulls?head=alcovaibe:{branch}&state=all&per_page=100') if p['state'] == 'open']
    if len(opened) != 1:
        raise ValueError('Channel PR creation was not confirmed')
    pr = opened[0]
    if (pr['base']['ref'] != 'main' or pr['head']['ref'] != branch or pr['head']['sha'] != head
            or pr['head']['repo']['full_name'] != REPO):
        raise ValueError('Channel PR no longer matches the checked head/repository/base')
    files = api(f'repos/{REPO}/pulls/{pr["number"]}/files?per_page=100')
    if len(files) != 1 or files[0]['filename'] != CHANNEL_PATH or files[0]['status'] != 'modified':
        raise ValueError('Channel PR includes unrelated changes')
    result = finish('pending_review', branch=branch, head_sha=head, pull_request=pr['number'], url=pr['html_url'])
    pending_review(branch, head)
    return result


def publish_channel(info, apk_path, output=Path('release-output')):
    try:
        return _prepare_channel(info, apk_path, output)
    except Exception:
        output.mkdir(parents=True, exist_ok=True)
        receipt = output/'channel-delivery.json'
        previous = json.loads(receipt.read_text()) if receipt.exists() else {}
        previous.update(schema=1, state='failed', tag=info.get('tag'), error='DELIVERY_FAILED')
        receipt.write_text(json.dumps(previous, ensure_ascii=False, indent=2)+'\n')
        raise


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--info', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    publish_channel(json.loads(args.info.read_text()), args.apk)
