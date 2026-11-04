#!/usr/bin/env bash
# Disposable emulator only: exercise the real updater -> scoped URI -> Android installer.
set -euo pipefail
expected_api=${1:?Expected API required}
serial=$(adb get-serialno | tr -d '\r')
[[ "$serial" == emulator-* ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == "$expected_api" ]]
mkdir -p update-delivery-results
trap 'adb logcat -d -b crash > update-delivery-results/crash.txt || true; adb exec-out screencap -p > update-delivery-results/final.png || true' EXIT
root_ready=false
for attempt in $(seq 1 5); do
  adb root > update-delivery-results/adb-root.txt 2>&1 || true
  if timeout 20 adb wait-for-device && [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then root_ready=true; break; fi
  sleep 2
done
[[ "$root_ready" == true ]]
package=app.alcovaibe.vkmusicexport
host=$package.updatecheck
appdata=/data/user/0/$package
adb install channel-evidence/baseline.apk
uid=$(adb shell stat -c %u "$appdata" | tr -d '\r')
[[ "$uid" =~ ^[0-9]+$ ]]
printf 'Private library sentinel: never clear app data during an APK update.\n' > update-delivery-results/protected.bin
adb push update-delivery-results/protected.bin /data/local/tmp/updater-protected.bin
adb shell "mkdir -p $appdata/files; cp /data/local/tmp/updater-protected.bin $appdata/files/updater-protected.bin; chown -R $uid:$uid $appdata/files; restorecon -RF $appdata"
adb install channel-evidence/host.apk
adb install channel-evidence/tests.apk
# No user/account sign-in, real release key or live VK calls are involved.
adb shell am instrument -w -r -e class app.alcovaibe.vkmusicexport.UpdateDeliveryDeviceTest "$host.test/androidx.test.runner.AndroidJUnitRunner" | tee update-delivery-results/instrumentation.txt
adb pull "$appdata/files/updater-protected.bin" update-delivery-results/protected-after.bin
cmp update-delivery-results/protected.bin update-delivery-results/protected-after.bin
adb shell dumpsys package "$package" > update-delivery-results/installed-package.txt
adb pull "/data/user/0/$host/files/updater-proof.json" update-delivery-results/updater-proof.json
python3 - "$expected_api" <<'PY'
import hashlib,json,re,sys
from pathlib import Path
out=Path('update-delivery-results')
log=(out/'instrumentation.txt').read_text()
assert re.search(r'OK \(1 test\)',log), 'Integration test did not complete exactly one passing test'
assert not any(s in log for s in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed','INSTRUMENTATION_ABORTED'])
proof=json.loads((out/'updater-proof.json').read_text())
manifest=json.loads(Path('channel-evidence/manifest.json').read_text())['release']
assert proof['api']==int(sys.argv[1]) and proof['from']==1343 and proof['to']==manifest['versionCode']
assert proof['sha256']==manifest['sha256'] and proof['system_installer'] is True
assert re.search(r'versionCode='+str(proof['to'])+r'\b',(out/'installed-package.txt').read_text())
assert (out/'protected.bin').read_bytes()==(out/'protected-after.bin').read_bytes()
proof['private_marker_preserved']=True
proof['marker_sha256']=hashlib.sha256((out/'protected-after.bin').read_bytes()).hexdigest()
(out/'result.json').write_text(json.dumps(proof,indent=2)+'\n')
print('PASS: public signed APK verified by the real updater, installed through Android UI; UID and private data retained.')
PY
