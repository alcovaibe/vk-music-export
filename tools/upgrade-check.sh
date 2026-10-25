#!/usr/bin/env bash
# Disposable rooted AVD only. No device, account or network access is needed.
set -euo pipefail
serial=$(adb get-serialno | tr -d '\r')
if [[ "$serial" != emulator-* ]]; then
  echo 'Refusing to overwrite a collection on a physical device. Use a disposable Android emulator.' >&2
  exit 2
fi
package=app.alcovaibe.vkmusicexport
appdata=/data/user/0/$package
mkdir -p upgrade-results
trap 'result=$?; echo "Upgrade check failed at line $LINENO (exit $result)"; adb logcat -d -b crash > upgrade-results/crash.txt || true; exit "$result"' ERR
# adbd may close the connection while switching to root on a fresh AVD.
root_ready=false
for attempt in $(seq 1 5); do
  adb root > upgrade-results/adb-root.txt 2>&1 || true
  if timeout 20 adb wait-for-device && [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then root_ready=true; break; fi
  sleep 2
done
[[ "$root_ready" == true ]]
adb wait-for-device
adb shell wm size 720x1600
adb shell wm density 294
adb shell am force-stop com.android.launcher3
adb install upgrade-input/previous.apk
adb shell am start -W -n "$package/.MainActivity"
adb shell am force-stop "$package"
uid=$(adb shell stat -c %u "$appdata" | tr -d '\r')
[[ "$uid" =~ ^[0-9]+$ ]]
adb push tools/fixtures/upgrade-collection.json /data/local/tmp/upgrade-collection.json
adb shell "mkdir -p $appdata/files; cp /data/local/tmp/upgrade-collection.json $appdata/files/collection.json; chown $uid:$uid $appdata/files/collection.json; chmod 600 $appdata/files/collection.json; restorecon -RF $appdata"
expected=$(sha256sum tools/fixtures/upgrade-collection.json | cut -d' ' -f1)
assert_collection() {
  actual=$(adb shell "sha256sum $appdata/files/collection.json" | cut -d' ' -f1 | tr -d '\r')
  test "$actual" = "$expected"
}
assert_visible() {
  # Old APK renders its saved source; the new APK must open the music home.
  local expected_text="${2:-Открыть мою музыку}"
  for attempt in $(seq 1 10); do
    # A fresh AVD can temporarily return a null accessibility root. Never read a stale dump.
    adb shell rm -f /sdcard/upgrade-window.xml
    rm -f "upgrade-results/$1.xml"
    if ! adb shell uiautomator dump /sdcard/upgrade-window.xml >"upgrade-results/$1-dump.txt" 2>&1 ||
       ! adb pull /sdcard/upgrade-window.xml "upgrade-results/$1.xml" >/dev/null 2>&1; then
      echo "UI snapshot unavailable (attempt $attempt); retrying."
      sleep 2
      continue
    fi
    # Emulator framework ANRs can cover the app after resizing the fresh AVD.
    # Only System UI / Quickstep dialogs are handled. App ANRs remain test failures.
    tap=$(python3 tools/emulator-dialog.py "upgrade-results/$1.xml")
    if [[ -n "$tap" ]]; then
      echo "Dismissing an emulator framework dialog; app ANRs are never dismissed."
      read -r x y <<< "$tap"
      adb shell input tap "$x" "$y"
      continue
    fi
    if grep -q "$expected_text" "upgrade-results/$1.xml"; then return; fi
    sleep 2
  done
  echo 'Expected release screen was not rendered.'
  adb logcat -d -b crash > upgrade-results/crash.txt
  return 1
}
adb shell am start -W -n "$package/.MainActivity"
assert_visible before UpgradeCheckFixture
adb shell am force-stop "$package"
assert_collection
# Simulate an interrupted legacy cleanup in this disposable fixture; never alter a physical device.
adb shell rm -f "$appdata/no_backup/legacy-audio-cleanup-v1"
# Seed retired files only after the old APK has stopped. The new APK must remove these exact paths.
adb shell "mkdir -p $appdata/files/native-audio $appdata/cache/native-audio-transfer /sdcard/Music/'VK Music Export'"
adb shell "echo OLD_PRIVATE_AUDIO > $appdata/files/native-audio/retired.mp3; echo '[]' > $appdata/files/native-audio-index.json; echo OLD_PARTIAL > $appdata/cache/native-audio-transfer/retired.media; echo CURRENT_PUBLIC_AUDIO > /sdcard/Music/'VK Music Export'/UpgradeMusicFixture.mp3"
public_expected=$(adb shell "sha256sum /sdcard/Music/'VK Music Export'/UpgradeMusicFixture.mp3" | cut -d' ' -f1 | tr -d '\r')
# A paused queue uses the exact v1 schema of published 0.9.1. It must survive install/restart without a network request.
python3 - <<'PYQUEUE'
import hashlib, json, sqlite3
from pathlib import Path
path=Path('upgrade-results/queue-fixture.db')
db=sqlite3.connect(path)
db.executescript("""
PRAGMA user_version=1;
CREATE TABLE tasks(ordinal INTEGER PRIMARY KEY AUTOINCREMENT,task_key TEXT UNIQUE NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,error TEXT,prepared TEXT);
CREATE TABLE files(task_key TEXT PRIMARY KEY,track_id TEXT NOT NULL,payload TEXT NOT NULL);
CREATE TABLE meta(name TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE INDEX task_states ON tasks(state,ordinal);
CREATE INDEX file_tracks ON files(track_id);
""")
key=hashlib.sha256(('Моя музыка'+'\0'+'12_99991'+'\0'+'1').encode()).hexdigest()
task={'key':key,'track':{'id':'12_99991','title':'UpgradeQueueFixture','artist':'CI','duration':1,'restricted':False,'album':None},'folders':['Моя музыка'],'position':1}
payload=json.dumps(task,ensure_ascii=False,separators=(',',':'))
db.execute('INSERT INTO tasks(task_key,payload,state) VALUES(?,?,?)',(key,payload,'PENDING'))
db.executemany('INSERT INTO meta VALUES(?,?)',[('status','PAUSED'),('source_issues','0'),('error','')]);db.commit();db.close()
Path('upgrade-results/queue-expected.json').write_text(payload)
PYQUEUE
adb push upgrade-results/queue-fixture.db /data/local/tmp/queue-fixture.db
adb shell "rm -f $appdata/no_backup/audio-export.db-wal $appdata/no_backup/audio-export.db-shm; cp /data/local/tmp/queue-fixture.db $appdata/no_backup/audio-export.db; chown -R $uid:$uid $appdata/files/native-audio $appdata/cache/native-audio-transfer $appdata/files/native-audio-index.json $appdata/no_backup/audio-export.db; restorecon -RF $appdata"
# This is the operation owners perform: replace the installed APK, never uninstall or clear data.
adb install -r upgrade-input/current.apk
assert_collection
adb shell dumpsys package "$package" > upgrade-results/package-after.txt
python3 - <<'PY'
import json, re
from pathlib import Path
info = json.loads(Path('release-output/build-info.json').read_text())
package = Path('upgrade-results/package-after.txt').read_text()
assert re.search(r'versionCode=' + str(info['versionCode']) + r'\b', package)
assert 'DEBUGGABLE' not in package
PY
adb shell am start -W -n "$package/.MainActivity"
assert_visible after
assert_collection
# Wait for the one-time IO migration, then verify the public audio and paused queue are intact.
for attempt in $(seq 1 10); do
  if adb shell test -f "$appdata/no_backup/legacy-audio-cleanup-v1"; then break; fi
  sleep 1
done
adb shell test -f "$appdata/no_backup/legacy-audio-cleanup-v1"
adb shell test ! -e "$appdata/files/native-audio"
adb shell test ! -e "$appdata/files/native-audio-index.json"
adb shell test ! -e "$appdata/cache/native-audio-transfer"
public_actual=$(adb shell "sha256sum /sdcard/Music/'VK Music Export'/UpgradeMusicFixture.mp3" | cut -d' ' -f1 | tr -d '\r')
[[ "$public_actual" == "$public_expected" ]]
# A real process restart must retain the previous process journal. No account/network needed.
adb shell am force-stop "$package"
adb pull "$appdata/no_backup/audio-export.db" upgrade-results/queue-after.db >/dev/null
if adb shell test -f "$appdata/no_backup/audio-export.db-wal"; then adb pull "$appdata/no_backup/audio-export.db-wal" upgrade-results/queue-after.db-wal >/dev/null; fi
python3 - <<'PYQUEUECHECK'
import sqlite3
from pathlib import Path
with sqlite3.connect('upgrade-results/queue-after.db') as db:
    rows=db.execute('SELECT payload,state FROM tasks').fetchall()
    assert rows==[(Path('upgrade-results/queue-expected.json').read_text(),'PENDING')], 'Queue changed during schema upgrade'
    assert db.execute("SELECT value FROM meta WHERE name='status'").fetchone()[0]=='PAUSED'
    assert db.execute('PRAGMA user_version').fetchone()[0]==2, 'Queue schema migration did not complete'
    assert db.execute("SELECT COUNT(*) FROM directories").fetchone()[0]==0
Path('upgrade-results/legacy-cleanup.txt').write_text('PASS: only retired private audio removed; public Music and paused queue retained.\n')
print('PASS: only retired private audio removed; public Music and paused queue retained.')
PYQUEUECHECK
adb shell am start -W -n "$package/.MainActivity"
assert_visible restarted
adb pull "$appdata/no_backup/diagnostics" upgrade-results/diagnostics >/dev/null
python3 - <<'PYDIAG'
import json
from pathlib import Path
runs=sorted(Path('upgrade-results/diagnostics').glob('*'))
assert len(runs)>=2, 'Previous process diagnostic journal missing'
for run in runs[-2:]:
    events=[]
    for part in run.glob('*.ndjson'):
        for line in part.read_text().splitlines():
            try: events.append(json.loads(line))
            except json.JSONDecodeError: pass # Interrupted tail is explicitly recoverable.
    assert any(e.get('type')=='application_create' for e in events), 'Startup event missing'
    assert all(e['run_id']==run.name and isinstance(e['seq'],int) for e in events)
assert sum(p.stat().st_size for p in Path('upgrade-results/diagnostics').rglob('*') if p.is_file())<=20_000_000
Path('upgrade-results/diagnostic-restart.txt').write_text('PASS: journal retained across actual force-stop/restart.\n')
PYDIAG
adb exec-out screencap -p > upgrade-results/updated-release.png
printf 'PASS: signed release -> higher-version signed release; music home displayed; legacy data, public music and queue intact.\n' | tee upgrade-results/result.txt
cp upgrade-results/result.txt release-output/upgrade-check.txt
