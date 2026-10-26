import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parent

class PublisherTest(unittest.TestCase):
    def publish(self, latest=None, existing=False, recovered=False):
        calls = []
        apk = 'VK-Music-Export-0.3.0-1024.apk'
        def gh(args, **kwargs):
            calls.append(args)
            if args[2] == 'create':
                return subprocess.CompletedProcess(args, 0)
            if '--json' in args and args[-1] == 'targetCommitish,assets':
                value = {'targetCommitish': 'a' * 40, 'assets': [{'name': apk}]}
                return subprocess.CompletedProcess(args, 0 if existing else 1, json.dumps(value), '')
            return subprocess.CompletedProcess(args, 0 if latest else 1, json.dumps({'tagName': latest}), '')
        with tempfile.TemporaryDirectory() as td:
            old = Path.cwd()
            os.chdir(td)
            try:
                out = Path('release-output');out.mkdir()
                (out / apk).write_bytes(b'APK fixture')
                (out / 'build-info.json').write_text(json.dumps({'tag':'v0.3.0-build.1024','apk':apk,
                    'commit':'a'*40,'versionName':'0.3.0','versionCode':1024}))
                (out / 'upgrade-check.txt').write_text('PASS: data retained\n')
                env = {'GITHUB_SHA': ('b' if recovered else 'a') * 40, 'RUNNER_TEMP':td,
                       'GITHUB_STEP_SUMMARY':str(Path(td)/'summary'), 'GITHUB_REPOSITORY':'alcovaibe/vk-music-export'}
                if recovered:
                    env['RELEASE_SOURCE_SHA'] = 'a'*40
                    env['RELEASE_TARGET_SHA'] = 'b'*40
                    info = json.loads((out/'build-info.json').read_text())
                    info['releaseCommit'] = 'b'*40
                    (out/'build-info.json').write_text(json.dumps(info))
                with patch.dict(os.environ, env, clear=True), patch('subprocess.run', side_effect=gh), patch('update_channel.publish_channel'):
                    runpy.run_path(str(TOOLS / 'publish-release.py'))
            finally:
                os.chdir(old)
        return calls

    def test_new_release_becomes_latest(self):
        calls = self.publish('v0.3.0-build.1023')
        self.assertIn('--latest=true', calls[-1])
        self.assertFalse(any(x.endswith(('build-info.json', 'upgrade-check.txt')) for x in calls[-1]))

    def test_old_recovery_cannot_downgrade_latest(self):
        calls = self.publish('v0.3.0-build.1025')
        self.assertIn('--latest=false', calls[-1])

    def test_existing_release_is_not_replaced(self):
        self.assertEqual(len(self.publish(existing=True)), 1)

    def test_recovery_tags_verified_identical_app_sources(self):
        call = self.publish(recovered=True)[-1]
        self.assertEqual(call[call.index('--target')+1], 'b'*40)

class EmulatorDialogTest(unittest.TestCase):
    def dialog(self, title, button):
        with tempfile.TemporaryDirectory() as td:
            file = Path(td) / 'window.xml'
            file.write_text(f'''<hierarchy><node resource-id="android:id/alertTitle" text="{title}"/>
            <node package="android" text="{button}" clickable="true" bounds="[10,20][30,40]"/></hierarchy>''')
            return subprocess.check_output(['python3', str(TOOLS/'emulator-dialog.py'), str(file)], text=True).strip()

    def test_system_ui_wait_button(self):
        self.assertEqual(self.dialog("System UI isn't responding", 'Wait'), '20 30')

    def test_app_anr_is_never_dismissed(self):
        self.assertEqual(self.dialog("VK Music Export isn't responding", 'Wait'), '')

    def test_quickstep_close_button(self):
        self.assertEqual(self.dialog("Quickstep isn't responding", 'Close app'), '20 30')

class UpgradeDeviceGuardTest(unittest.TestCase):
    def test_published_baseline_home_retries_missing_snapshot_without_accepting_stale_xml(self):
        script=(TOOLS/'upgrade-check.sh').read_text()
        function=script[script.index('assert_visible() {'):script.index('\nadb shell am start -W',script.index('assert_visible() {'))]
        before_call=next(line for line in script.splitlines() if line.startswith('assert_visible before'))
        with tempfile.TemporaryDirectory() as td:
            directory=Path(td)
            (directory/'upgrade-results').mkdir();(directory/'tools').mkdir()
            (directory/'tools/emulator-dialog.py').write_text((TOOLS/'emulator-dialog.py').read_text())
            # A stale successful snapshot must not mask failure of the first dump.
            (directory/'upgrade-results/before.xml').write_text('<hierarchy><node text="Открыть мою музыку"/></hierarchy>')
            adb=directory/'adb'
            adb.write_text('''#!/bin/bash
case "$*" in
 "shell rm -f /sdcard/upgrade-window.xml") rm -f remote.xml;;
 "shell uiautomator dump /sdcard/upgrade-window.xml")
   n=$(cat attempts 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > attempts
   if [[ "$n" == 1 ]]; then echo 'ERROR: null root node' >&2; else echo '<hierarchy><node text="Открыть мою музыку"/></hierarchy>' > remote.xml; fi;;
 "pull /sdcard/upgrade-window.xml upgrade-results/before.xml") cp remote.xml upgrade-results/before.xml;;
 *) exit 3;;
esac
''')
            adb.chmod(0o755)
            sleep=directory/'sleep';sleep.write_text('#!/bin/bash\nexit 0\n');sleep.chmod(0o755)
            result=subprocess.run(['bash','-c','set -euo pipefail\n'+function+'\n'+before_call],cwd=td,
                env=dict(os.environ,PATH=td+os.pathsep+os.environ['PATH']),capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual((directory/'attempts').read_text().strip(),'2')
            self.assertIn('UI snapshot unavailable',result.stdout)

    def test_physical_device_is_rejected_before_root_or_install(self):
        with tempfile.TemporaryDirectory() as td:
            directory = Path(td)
            adb = directory / 'adb'
            adb.write_text('#!/bin/bash\nif [[ "$1" == get-serialno ]]; then echo personal-phone; else touch "$UNSAFE_COMMAND"; exit 1; fi\n')
            adb.chmod(0o755)
            marker = directory / 'unsafe-command'
            env = dict(os.environ, PATH=td + os.pathsep + os.environ['PATH'], UNSAFE_COMMAND=str(marker))
            result = subprocess.run(['bash', str(TOOLS/'upgrade-check.sh')], cwd=td, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn('physical device', result.stderr)
            self.assertFalse(marker.exists())

if __name__ == '__main__': unittest.main()
