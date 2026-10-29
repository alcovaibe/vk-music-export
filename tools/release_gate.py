"""Fail closed before promoting an exact, manually accepted release candidate."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

SCENARIOS = (
    'real_vk_login_and_renewal', 'large_library_export', 'screen_off_and_process_restart',
    'network_loss_and_retry', 'account_switch_and_logout', 'upgrade_existing_archive',
    'external_players_tags_artwork_and_playlist', 'duplicate_scan_and_safe_removal',
)
BUILD_INPUTS = (
    'app', 'gradle', 'config', 'build.gradle.kts', 'gradle.properties',
    'settings.gradle.kts', 'version.properties', 'gradlew', 'gradlew.bat',
)


def validate_acceptance(evidence, info, apk):
    if evidence.get('status') != 'passed':
        raise ValueError('Public release acceptance has not passed')
    if evidence.get('source_commit') != info['commit']:
        raise ValueError('Acceptance covers another source commit')
    if evidence.get('version_code') != info['versionCode']:
        raise ValueError('Acceptance covers another APK version')
    if evidence.get('apk_sha256') != hashlib.sha256(apk.read_bytes()).hexdigest():
        raise ValueError('Acceptance does not cover this exact APK')
    for field in ('tester', 'tested_at', 'candidate_run', 'device', 'android_version'):
        value = evidence.get(field)
        if not isinstance(value, str) or len(value.strip()) < 3 or value.lower() in ('pending', 'todo', 'unknown'):
            raise ValueError(f'Missing acceptance evidence: {field}')
    if not re.fullmatch(r'[0-9a-f]{40}', evidence['source_commit']):
        raise ValueError('Invalid acceptance commit')
    results = evidence.get('scenarios', {})
    for name in SCENARIOS:
        item = results.get(name, {})
        if item.get('status') != 'passed' or len(item.get('evidence', '').strip()) < 20:
            raise ValueError(f'Manual scenario not accepted: {name}')


def verify_stable(info, apk, path=Path('docs/release-acceptance/current.json')):
    # The evidence commit may follow the APK commit. Android/build inputs must match.
    evidence = json.loads(path.read_text())
    validate_acceptance(evidence, info, apk)
    subprocess.run(['git', 'diff', '--exit-code', info['commit'], 'HEAD', '--', *BUILD_INPUTS], check=True)
    for file in ('gradle/verification-metadata.xml', 'app/gradle.lockfile'):
        if not Path(file).is_file():
            raise ValueError(f'Dependency verification is missing: {file}')
    return evidence


if __name__ == '__main__':
    info = json.loads(Path('release-output/build-info.json').read_text())
    verify_stable(info, Path('release-output') / info['apk'])
    print('PASS: exact APK manually accepted; Android/build inputs unchanged.')
