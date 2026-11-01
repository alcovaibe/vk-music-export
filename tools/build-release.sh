#!/usr/bin/env bash
set -euo pipefail
: "${RELEASE_KEYSTORE_PATH:?Signing key is required}"
: "${APP_VERSION_CODE:?A monotonic versionCode is required}"
build_tools="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p release-output upgrade-input
# Download the actual current stable, plus the oldest supported upgrade baseline.
python3 tools/download-baselines.py
./gradlew --dependency-verification strict lintRelease assembleRelease --stacktrace
cp app/build/outputs/apk/release/app-release.apk upgrade-input/current.apk
expected=$(openssl x509 -in config/release-certificate.pem -outform DER | sha256sum | cut -d' ' -f1)
for apk in upgrade-input/previous.apk upgrade-input/oldest.apk upgrade-input/current.apk; do
  "$build_tools/apksigner" verify --verbose --print-certs "$apk" > "$apk.signing.txt"
  actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$apk.signing.txt")
  test "$actual" = "$expected" || { echo 'Signing certificate changed; publication blocked.'; exit 1; }
done
"$build_tools/aapt" dump badging upgrade-input/previous.apk > upgrade-input/previous.manifest.txt
"$build_tools/aapt" dump badging upgrade-input/current.apk > upgrade-input/current.manifest.txt
python3 - <<'PY'
import json, os, shutil, sys
from pathlib import Path
sys.path.insert(0, 'tools')
from apk_manifest import configured_min_sdk, release_manifest

previous = release_manifest(Path('upgrade-input/previous.manifest.txt').read_text())
current = release_manifest(Path('upgrade-input/current.manifest.txt').read_text(), configured_min_sdk())
code, version = current['versionCode'], current['versionName']
assert code == int(os.environ['APP_VERSION_CODE']) and previous['versionCode'] < code
filename = f'VK-Music-Export-{version}-{code}.apk'
shutil.copyfile('upgrade-input/current.apk', f'release-output/{filename}')
Path('release-output/build-info.json').write_text(json.dumps({
    **current, 'commit': os.environ['GITHUB_SHA'],
    'buildRun': os.environ['GITHUB_RUN_ID'],
    'tag': f'v{version}-build.{code}', 'apk': filename,
}, indent=2) + '\n')
PY
cp config/release-certificate.pem release-output/
cp LICENSE NOTICE release-output/
(cd release-output && sha256sum *.apk > SHA256SUMS.txt)

# Public evidence only: permits checking uploaded assets against this exact build.
python3 - <<'PYEVIDENCE'
import hashlib, json
from pathlib import Path
info = json.loads(Path('release-output/build-info.json').read_text())
apk = Path('release-output') / info['apk']
print('RELEASE_BUILD_INFO=' + json.dumps(info, sort_keys=True))
print('RELEASE_APK_SHA256=' + hashlib.sha256(apk.read_bytes()).hexdigest())
print('RELEASE_PUBLIC_CERT_PEM_SHA256=' + hashlib.sha256(Path('release-output/release-certificate.pem').read_bytes()).hexdigest())
PYEVIDENCE
