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
    def publish(self, latest=None, existing=False, recovered=False, channel='stable', include_legal=True):
        calls = []
        apk = 'VK-Music-Export-0.3.0-1024.apk'
        def gh(args, **kwargs):
            calls.append(args)
            if args[2] == 'create':
                self.release_notes = Path(args[args.index('--notes-file') + 1]).read_text()
                return subprocess.CompletedProcess(args, 0)
            if '--json' in args and args[-1] == 'targetCommitish,assets,prerelease':
                value = {'targetCommitish': 'a' * 40, 'assets': [{'name': apk}], 'prerelease':channel == 'rc'}
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
                for asset in ('SHA256SUMS.txt','release-certificate.pem','sbom.cdx.json','osv.json'):
                    (out / asset).write_text('fixture')
                if include_legal:
                    for asset in ('LICENSE', 'NOTICE'):
                        (out / asset).write_text('Synthetic license fixture')
                Path('CHANGELOG.md').write_text('## [0.3.0]\n\nVersion-specific fixes.\n')
                env = {'GITHUB_SHA': ('b' if recovered else 'a') * 40, 'RUNNER_TEMP':td,
                       'GITHUB_STEP_SUMMARY':str(Path(td)/'summary'), 'GITHUB_REPOSITORY':'alcovaibe/vk-music-export',
                       'RELEASE_CHANNEL':channel, 'GITHUB_RUN_ID':'2000'}
                if recovered:
                    env['RELEASE_SOURCE_SHA'] = 'a'*40
                    env['RELEASE_TARGET_SHA'] = 'b'*40
                    info = json.loads((out/'build-info.json').read_text())
                    info['releaseCommit'] = 'b'*40
                    (out/'build-info.json').write_text(json.dumps(info))
                with patch.dict(os.environ, env, clear=True), patch('subprocess.run', side_effect=gh), patch('update_channel.publish_channel') as channel_update, patch('release_gate.verify_stable') as gate:
                    runpy.run_path(str(TOOLS / 'publish-release.py'))
                    self.assertEqual(channel_update.call_count, int(channel == 'stable'))
                    self.assertEqual(gate.call_count, int(channel == 'stable'))
            finally:
                os.chdir(old)
        return calls

    def test_new_release_becomes_latest(self):
        calls = self.publish('v0.3.0-build.1023')
        self.assertIn('--latest=true', calls[-1])
        self.assertEqual(calls[-1][4:calls[-1].index('--target')],
                         ['release-output/VK-Music-Export-0.3.0-1024.apk', 'release-output/SHA256SUMS.txt'])
        self.assertIn('/actions/runs/2000', self.release_notes)

    def test_rc_never_becomes_latest_or_changes_stable_channel(self):
        calls = self.publish(channel='rc')
        self.assertIn('--prerelease', calls[-1])
        self.assertIn('--latest=false', calls[-1])
        self.assertIn('v0.3.0-build.1024-rc', calls[-1])
        self.assertEqual(calls[-1][4:calls[-1].index('--target')],
                         ['release-output/VK-Music-Export-0.3.0-1024.apk', 'release-output/SHA256SUMS.txt'])

    def test_old_recovery_cannot_downgrade_latest(self):
        calls = self.publish('v0.3.0-build.1025')
        self.assertIn('--latest=false', calls[-1])

    def test_existing_release_is_not_replaced(self):
        self.assertEqual(len(self.publish(existing=True)), 1)

    def test_recovery_tags_verified_identical_app_sources(self):
        call = self.publish(recovered=True)[-1]
        self.assertEqual(call[call.index('--target')+1], 'b'*40)

    def test_release_provides_license_and_exact_apk_source(self):
        call = self.publish(recovered=True)[-1]
        self.assertNotIn('release-output/LICENSE', call)
        self.assertNotIn('release-output/NOTICE', call)
        self.assertIn('включены в APK и архив исходников', self.release_notes)
        self.assertIn('GPL-3.0-or-later', self.release_notes)
        self.assertIn('/archive/' + 'a'*40 + '.tar.gz', self.release_notes)
        self.assertNotIn('/archive/' + 'b'*40 + '.tar.gz', self.release_notes)

    def test_missing_license_blocks_publication(self):
        with self.assertRaisesRegex(AssertionError, 'Release evidence is incomplete'):
            self.publish(include_legal=False)

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
    def test_large_badging_output_selects_oldest_screen_and_schema_under_pipefail(self):
        script=(TOOLS/'upgrade-check.sh').read_text()
        conditions=[line.split('; then',1)[0] for line in script.splitlines()
                    if line.startswith('if "$build_tools/aapt"')]
        self.assertEqual(len(conditions),2)
        with tempfile.TemporaryDirectory() as td:
            aapt=Path(td)/'aapt'
            aapt.write_text('''#!/usr/bin/env python3
import os, signal
signal.signal(signal.SIGPIPE, signal.SIG_DFL)
os.write(1, ("package: name='fixture' versionCode='"+os.environ['BASELINE_CODE']+"'\\n").encode())
for _ in range(256):
    os.write(1, b"additional manifest data\\n"*1024)
''')
            aapt.chmod(0o755)
            for condition in conditions:
                for code,expected in [('1087','oldest'),('1210','current')]:
                    with self.subTest(condition=condition,code=code):
                        result=subprocess.run(['bash','-c','set -euo pipefail\n'+condition+
                            '; then echo oldest; else echo current; fi'],
                            env=dict(os.environ,build_tools=td,BASELINE_CODE=code),capture_output=True,text=True)
                        self.assertEqual(result.returncode,0,result.stderr)
                        self.assertEqual(result.stdout.strip(),expected,result.stderr)

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

    def test_updated_home_and_restart_require_new_entry_while_baseline_keeps_old_entry(self):
        script=(TOOLS/'upgrade-check.sh').read_text()
        function=script[script.index('assert_visible() {'):script.index('\nadb shell am start -W',script.index('assert_visible() {'))]
        calls=[line for line in script.splitlines() if line.startswith('assert_visible ')]
        self.assertIn('assert_visible before "Открыть мою музыку"',calls)
        for phase in ('after','restarted'):
            call=next(line for line in calls if line.startswith('assert_visible '+phase+' '))
            self.assertEqual(call,f'assert_visible {phase} "VK Music"')
            for label,expected in [('VK Music',0),('Открыть мою музыку',1),('VK Music Export',1)]:
                with tempfile.TemporaryDirectory() as td:
                    directory=Path(td);(directory/'upgrade-results').mkdir();(directory/'tools').mkdir()
                    (directory/'tools/emulator-dialog.py').write_text((TOOLS/'emulator-dialog.py').read_text())
                    adb=directory/'adb'
                    adb.write_text('''#!/bin/bash
case "$*" in
 "shell rm -f /sdcard/upgrade-window.xml") rm -f remote.xml;;
 "shell uiautomator dump /sdcard/upgrade-window.xml") printf '<hierarchy><node text="%s"/></hierarchy>' "$SNAPSHOT_LABEL" > remote.xml;;
 pull*) cp remote.xml "$3";;
 "logcat -d -b crash") exit 0;;
 *) exit 3;;
esac
''')
                    adb.chmod(0o755)
                    sleep=directory/'sleep';sleep.write_text('#!/bin/bash\nexit 0\n');sleep.chmod(0o755)
                    result=subprocess.run(['bash','-c','set -euo pipefail\n'+function+'\n'+call],cwd=td,
                        env=dict(os.environ,PATH=td+os.pathsep+os.environ['PATH'],SNAPSHOT_LABEL=label),capture_output=True,text=True)
                    self.assertEqual(result.returncode,expected,result.stderr)

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

class UpgradeEvidenceTest(unittest.TestCase):
    def check(self, failing):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            (root/'upgrade-input').mkdir();(root/'release-output').mkdir()
            (root/'upgrade-input/previous.apk').write_text('stable')
            (root/'upgrade-input/oldest.apk').write_text('oldest')
            stub=root/'bash'
            stub.write_text("""#!/bin/sh
mkdir -p upgrade-results
baseline=$(cat upgrade-input/previous.apk)
printf 'PASS: %s fixture\\n' "$baseline" > upgrade-results/result.txt
printf 'PASS: one baseline only\\n' > release-output/upgrade-check.txt
if [ "$baseline" = "$FAIL_BASELINE" ]; then exit 1; fi
""")
            stub.chmod(0o755)
            result=subprocess.run(['/bin/bash',str(TOOLS/'upgrade-check-all.sh')],cwd=root,
                env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],FAIL_BASELINE=failing),capture_output=True,text=True)
            self.assertEqual(result.returncode,0 if not failing else 1,result.stderr)
            self.assertEqual((root/'upgrade-input/previous.apk').read_text(),'stable')
            self.assertTrue((root/'upgrade-results/stable/result.txt').exists())
            self.assertEqual((root/'upgrade-results/oldest/result.txt').exists(),failing!='stable')
            aggregate=root/'release-output/upgrade-check.txt'
            self.assertEqual(aggregate.exists(),not bool(failing))
            if aggregate.exists(): self.assertIn('current stable and oldest',aggregate.read_text())

    def test_both_baselines_produce_one_aggregate_pass(self): self.check('')
    def test_oldest_failure_keeps_both_reports_without_aggregate_pass(self): self.check('oldest')
    def test_stable_failure_keeps_its_report_without_aggregate_pass(self): self.check('stable')


if __name__ == '__main__': unittest.main()
