"""One explicit owner-requested PR publication. Never represents manual device acceptance as passed."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

REQUEST = Path('docs/release-requests/pr-48.json')


def validate_context(request, env, event, now):
    assert request['authorization'] == 'owner_requested_latest'
    assert request['manual_acceptance'] == 'pending'
    assert request['repository'] == env['GITHUB_REPOSITORY'] == 'alcovaibe/vk-music-export'
    assert request['branch'] == 'codex/music-library-redesign'
    assert env['GITHUB_REF'] == 'refs/heads/' + request['branch']
    assert env['GITHUB_EVENT_NAME'] == 'push'
    assert '[publish-pr-48]' in event['head_commit']['message']
    assert request['pull_request'] == 48 and request['version_name'] == '0.11.0'
    assert now <= dt.datetime.fromisoformat(request['expires_at'].replace('Z', '+00:00'))
    assert re.fullmatch(r'[0-9a-f]{40}', env['GITHUB_SHA'])
    return request


def validate_assets(info, release, apk, latest):
    assert info['versionName'] == '0.11.0' and info['applicationId'] == 'app.alcovaibe.vkmusicexport'
    assert info['minSdk'] == 36 and info['versionCode'] > 1236
    assert info['tag'] == f"v{info['versionName']}-build.{info['versionCode']}"
    assert release['target_commitish'] == info['commit']
    assets = [a for a in release['assets'] if a['name'] == info['apk']]
    assert len(assets) == 1 and assets[0]['state'] == 'uploaded'
    assert assets[0]['size'] == apk.stat().st_size
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert assets[0]['digest'] == 'sha256:' + digest
    assert len([a for a in release['assets'] if a['name'] == 'SHA256SUMS.txt']) == 1
    if latest['tag_name'] != info['tag']:
        code = re.fullmatch(r'v.+-build\.(\d+)', latest['tag_name'])
        assert code and int(code[1]) < info['versionCode'], 'A newer Latest must never be replaced'
    return digest


def api(endpoint, method='GET', payload=None):
    command = ['gh', 'api', endpoint, '--method', method]
    if payload is None:
        return json.loads(subprocess.check_output(command, text=True))
    with tempfile.NamedTemporaryFile(mode='w', suffix='.json') as body:
        json.dump(payload, body); body.flush()
        return json.loads(subprocess.check_output(command + ['--input', body.name], text=True))


def main(promote):
    request = json.loads(REQUEST.read_text())
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    validate_context(request, os.environ, event, dt.datetime.now(dt.timezone.utc))
    for path, expected in request['build_inputs'].items():
        assert re.fullmatch(r'[0-9a-f]{40}', expected)
        actual = subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], text=True).strip()
        assert actual == expected, f'Owner request covers different build inputs: {path}'
    assert 'versionName=0.11.0\n' in Path('version.properties').read_text()
    print('PASS: exact owner-requested PR #48 build inputs; real-device acceptance remains pending.')
    if not promote:
        return
    info = json.loads(Path('release-output/build-info.json').read_text())
    assert info['commit'] == os.environ['GITHUB_SHA']
    assert info['buildRun'] == os.environ['GITHUB_RUN_ID']
    assert info['versionCode'] == 1000 + int(os.environ['GITHUB_RUN_NUMBER'])
    assert Path('release-output/upgrade-check.txt').read_text().startswith('PASS:')
    apk = Path('release-output') / info['apk']
    # build-release.sh checked this certificate already; independently check the exact file being promoted.
    certificate = subprocess.check_output(['openssl', 'x509', '-in', 'config/release-certificate.pem', '-outform', 'DER'])
    signature = subprocess.check_output([str(Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner'),
        'verify', '--print-certs', str(apk)], text=True)
    assert 'Signer #1 certificate SHA-256 digest: ' + hashlib.sha256(certificate).hexdigest() in signature
    repo = 'repos/' + os.environ['GITHUB_REPOSITORY']
    stable_tag = info['tag']; rc_tag = stable_tag + '-rc'
    release = api(repo + '/releases/tags/' + rc_tag)
    latest = api(repo + '/releases/latest')
    digest = validate_assets(info, release, apk, latest)
    assert digest + '  ' + info['apk'] in Path('release-output/SHA256SUMS.txt').read_text()
    api(repo + '/git/refs', 'POST', {'ref': 'refs/tags/' + stable_tag, 'sha': info['commit']})
    notes = release['body'].replace(' · rc', ' · stable').replace(
        'Для RC ручная приёмка реального аккаунта и фоновой работы ещё обязательна перед stable.',
        'Ручная приёмка на реальном устройстве пока не подтверждена; публикация выполнена по явному решению владельца из PR #48.')
    updated = api(repo + '/releases/' + str(release['id']), 'PATCH', {
        'tag_name': stable_tag, 'target_commitish': info['commit'], 'prerelease': False, 'draft': False,
        'name': f"VK Music Export {info['versionName']} · {info['versionCode']}", 'body': notes, 'make_latest': 'true'})
    assert not updated['prerelease'] and not updated['draft'] and updated['tag_name'] == stable_tag
    assert api(repo + '/releases/latest')['id'] == updated['id']
    print('OWNER_REQUESTED_LATEST=' + updated['html_url'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--promote', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    assert args.promote != args.check
    main(args.promote)
