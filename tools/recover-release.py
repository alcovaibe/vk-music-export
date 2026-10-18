"""Re-use signed binaries only after verifying provenance, checks, hashes and certificate."""
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

repo = os.environ['GITHUB_REPOSITORY']
assert os.environ['GITHUB_REF'] == 'refs/heads/main'

def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path]))

def load(folder, run_id):
    assert run_id.isdecimal()
    run = api(f'repos/{repo}/actions/runs/{run_id}')
    assert run['repository']['full_name'] == repo and run['head_branch'] == 'main'
    assert run['path'] == '.github/workflows/android.yml'
    assert run['event'] in ('push', 'workflow_dispatch')
    jobs = api(f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100')['jobs']
    for name in ('build', 'interface'):
        assert any(j['name'] == name and j['conclusion'] == 'success' for j in jobs), f'{name} did not pass'
    out = Path(folder) / 'release-output'
    info = json.loads((out / 'build-info.json').read_text())
    assert info['commit'] == run['head_sha']
    assert re.fullmatch(r'VK-Music-Export-[0-9.]+-[0-9]+\.apk', info['apk'])
    apk = out / info['apk']
    expected = (out / 'SHA256SUMS.txt').read_text().strip().split()
    assert len(expected) == 2 and expected[1] == info['apk']
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected[0]
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    sig = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    cert = subprocess.check_output(['openssl', 'x509', '-in', 'config/release-certificate.pem', '-outform', 'DER'])
    actual = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', sig)
    assert actual and actual[1] == hashlib.sha256(cert).hexdigest()
    manifest = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    assert f"package: name='app.alcovaibe.vkmusicexport' versionCode='{info['versionCode']}' versionName='{info['versionName']}'" in manifest
    assert "sdkVersion:'36'" in manifest and 'application-debuggable' not in manifest
    assert info['applicationId'] == 'app.alcovaibe.vkmusicexport' and info['minSdk'] == 36
    assert info['tag'] == f"v{info['versionName']}-build.{info['versionCode']}"
    return out, info, apk

current, info, current_apk = load('candidate', os.environ['CANDIDATE_RUN'])
previous, old, previous_apk = load('baseline', os.environ['BASELINE_RUN'])
assert old['versionCode'] < info['versionCode']
# GitHub's built-in token cannot tag an older workflow revision. Tag the verification
# commit only after proving every Android application/build input is byte-identical.
build_inputs = {'app', 'gradle', 'config', 'build.gradle.kts', 'gradle.properties',
                'settings.gradle.kts', 'version.properties', 'gradlew', 'gradlew.bat'}
def inputs_at(sha):
    tree = api(f'repos/{repo}/git/trees/{sha}')['tree']
    found = {e['path']: (e['mode'], e['type'], e['sha']) for e in tree if e['path'] in build_inputs}
    assert set(found) == build_inputs
    return found
release_commit = os.environ['GITHUB_SHA']
assert inputs_at(info['commit']) == inputs_at(release_commit), 'Application/build sources changed; rebuild the APK'
Path('upgrade-input').mkdir()
shutil.copyfile(previous_apk, 'upgrade-input/previous.apk')
shutil.copyfile(current_apk, 'upgrade-input/current.apk')
shutil.copytree(current, 'release-output')
info['releaseCommit'] = release_commit
info['verificationRun'] = os.environ['GITHUB_RUN_ID']
Path('release-output/build-info.json').write_text(json.dumps(info, indent=2) + '\n')
with open(os.environ['GITHUB_ENV'], 'a') as env:
    env.write(f"RELEASE_SOURCE_SHA={info['commit']}\nRELEASE_TARGET_SHA={release_commit}\n")
print(f"Verified signed APK provenance and checks: {old['versionCode']} -> {info['versionCode']}")
