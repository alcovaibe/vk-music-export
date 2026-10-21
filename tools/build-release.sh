#!/usr/bin/env bash
set -euo pipefail
: "${RELEASE_KEYSTORE_PATH:?Signing key is required}"
: "${APP_VERSION_CODE:?A monotonic versionCode is required}"
build_tools="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p release-output upgrade-input
# Verify the actual owner-installed release, not a newly rebuilt surrogate.
gh release download v0.9.0-build.1067 --pattern 'VK-Music-Export-0.9.0-1067.apk' --dir upgrade-input --clobber
mv upgrade-input/VK-Music-Export-0.9.0-1067.apk upgrade-input/previous.apk
./gradlew lintRelease assembleRelease --stacktrace
cp app/build/outputs/apk/release/app-release.apk upgrade-input/current.apk
expected=$(openssl x509 -in config/release-certificate.pem -outform DER | sha256sum | cut -d' ' -f1)
for apk in upgrade-input/previous.apk upgrade-input/current.apk; do
  "$build_tools/apksigner" verify --verbose --print-certs "$apk" > "$apk.signing.txt"
  actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$apk.signing.txt")
  test "$actual" = "$expected" || { echo 'Signing certificate changed; publication blocked.'; exit 1; }
done
"$build_tools/aapt" dump badging upgrade-input/previous.apk > upgrade-input/previous.manifest.txt
"$build_tools/aapt" dump badging upgrade-input/current.apk > upgrade-input/current.manifest.txt
python3 - <<'PY'
import json, os, re, shutil
from pathlib import Path

def manifest(name):
    text = Path(f'upgrade-input/{name}.manifest.txt').read_text()
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text)
    assert pkg and pkg[1] == 'app.alcovaibe.vkmusicexport'
    assert "sdkVersion:'36'" in text and 'application-debuggable' not in text
    return int(pkg[2]), pkg[3]
old, _ = manifest('previous')
code, version = manifest('current')
assert code == int(os.environ['APP_VERSION_CODE']) and old < code
filename = f'VK-Music-Export-{version}-{code}.apk'
shutil.copyfile('upgrade-input/current.apk', f'release-output/{filename}')
Path('release-output/build-info.json').write_text(json.dumps({
    'versionName': version, 'versionCode': code, 'minSdk': 36,
    'applicationId': 'app.alcovaibe.vkmusicexport', 'commit': os.environ['GITHUB_SHA'],
    'tag': f'v{version}-build.{code}', 'apk': filename,
}, indent=2) + '\n')
PY
cp config/release-certificate.pem release-output/
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
