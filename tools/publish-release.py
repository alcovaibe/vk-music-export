"""Publish a tested RC, or promote the exact manually accepted APK to stable."""
import json
import os
import re
import subprocess
from pathlib import Path
from release_gate import verify_stable
from release_notes import notes_for
from update_channel import publish_channel

info = json.loads(Path('release-output/build-info.json').read_text())
channel = os.environ['RELEASE_CHANNEL']
assert channel in ('rc', 'stable'), 'Explicit release channel is required'
source_sha = os.environ.get('RELEASE_SOURCE_SHA', os.environ['GITHUB_SHA'])
assert re.fullmatch(r'[0-9a-f]{40}', source_sha) and info['commit'] == source_sha
release_commit = os.environ.get('RELEASE_TARGET_SHA', source_sha)
assert re.fullmatch(r'[0-9a-f]{40}', release_commit)
assert info.get('releaseCommit', source_sha) == release_commit
assert Path('release-output/upgrade-check.txt').read_text().startswith('PASS:')
apk = Path('release-output') / info['apk']
if channel == 'stable':
    verify_stable(info, apk)
tag = info['tag'] + ('-rc' if channel == 'rc' else '')
changes = notes_for(info['versionName'])
existing = subprocess.run(['gh', 'release', 'view', tag, '--json', 'targetCommitish,assets,prerelease'], capture_output=True, text=True)
if existing.returncode == 0:
    release = json.loads(existing.stdout)
    assert release['targetCommitish'] == release_commit, 'Tag points to another source revision'
    assert release['prerelease'] == (channel == 'rc'), 'Existing release has another channel'
    assert info['apk'] in [a['name'] for a in release['assets']], 'Existing release is incomplete; inspect it manually'
    print(f'Release {tag} already exists; assets were not replaced.')
else:
    notes = Path(os.environ['RUNNER_TEMP']) / 'release-notes.md'
    notes.write_text(f"VK Music Export {info['versionName']} · сборка {info['versionCode']} · {channel}\n\n{changes}\n\n"
        f"Исходный коммит APK: `{source_sha}`.\nКоммит проверки: `{release_commit}`.\n\n"
        'Проверки эмулятора и обновления описаны в upgrade-check.txt. '
        'Для RC ручная приёмка реального аккаунта и фоновой работы ещё обязательна перед stable.\n')
    make_latest = channel == 'stable'
    if make_latest:
        latest = subprocess.run(['gh', 'release', 'view', '--json', 'tagName'], capture_output=True, text=True)
        if latest.returncode == 0:
            match = re.search(r'-build\.(\d+)$', json.loads(latest.stdout)['tagName'])
            assert match, 'Latest release has an unknown version code'
            make_latest = info['versionCode'] > int(match[1])
    names = (info['apk'], 'SHA256SUMS.txt', 'release-certificate.pem', 'build-info.json', 'upgrade-check.txt', 'sbom.cdx.json', 'osv.json')
    assets = [str(Path('release-output') / name) for name in names]
    assert all(Path(asset).is_file() for asset in assets), 'Public release evidence is incomplete'
    command = ['gh', 'release', 'create', tag, *assets, '--target', release_commit,
        '--title', f"VK Music Export {info['versionName']} · {info['versionCode']}" + (' RC' if channel == 'rc' else ''),
        '--notes-file', str(notes), '--latest=' + str(make_latest).lower()]
    if channel == 'rc':
        command.append('--prerelease')
    subprocess.run(command, check=True)
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
    summary.write(f"Release channel: **{channel}**\n\nhttps://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/tag/{tag}\n")
if channel == 'stable':
    publish_channel(info, apk)
