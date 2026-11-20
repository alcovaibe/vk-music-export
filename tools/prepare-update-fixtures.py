"""Build an isolated, signed APK pair for the Android updater integration test."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from channel_contract import file_hash, validate_manifest
from channel_public import verify_apk

BASELINE_VERSION_CODE = 1343
output = Path('channel-evidence')
candidate = json.loads((output / 'manifest.json').read_text())
release = validate_manifest(candidate)
if release['versionCode'] <= BASELINE_VERSION_CODE:
    raise ValueError('Updater integration requires a strictly newer candidate than its baseline')

# Verify the original public bytes before producing an emulator-only copy.
public_apk = output / release['apk']
verify_apk(public_apk, candidate)

subprocess.run(
    ['./gradlew', '--dependency-verification', 'strict', 'assembleDebug',
     '-Pkotlin.compiler.execution.strategy=in-process', '--stacktrace'],
    env={**os.environ, 'APP_VERSION_CODE': str(BASELINE_VERSION_CODE)}, check=True,
)
baseline = Path('app/build/outputs/apk/debug/app-debug.apk')
assets = Path('app/src/androidTest/assets/update-fixtures')
assets.mkdir(parents=True, exist_ok=True)
fixture_apk = assets / 'candidate.apk'
signer = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner'


def create_key(path):
    subprocess.run(
        ['keytool', '-genkeypair', '-keystore', str(path), '-storepass', 'fixture-only',
         '-keypass', 'fixture-only', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048',
         '-validity', '2', '-dname', 'CN=VK Music Export updater fixture'],
        check=True, capture_output=True,
    )


def sign(source, destination, key):
    subprocess.run(
        [str(signer), 'sign', '--ks', str(key), '--ks-pass', 'pass:fixture-only',
         '--out', str(destination), str(source)], check=True,
    )


with tempfile.TemporaryDirectory() as directory:
    key = Path(directory) / 'update.jks'
    wrong_key = Path(directory) / 'wrong-signer.jks'
    create_key(key)
    create_key(wrong_key)
    sign(baseline, output / 'baseline.apk', key)
    sign(public_apk, fixture_apk, key)
    sign(public_apk, assets / 'wrong-signer.apk', wrong_key)

fixture = {**candidate, 'release': {**release, 'size': fixture_apk.stat().st_size,
                                    'sha256': file_hash(fixture_apk)}}
validate_manifest(fixture)
fixture_manifest = output / 'integration-manifest.json'
fixture_manifest.write_text(json.dumps(fixture, indent=2) + '\n', encoding='utf-8')
shutil.copyfile(fixture_manifest, assets / 'manifest.json')
print('Prepared emulator-only APKs with matching and mismatched signers.')
