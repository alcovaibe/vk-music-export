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
                if recovered: env['RELEASE_SOURCE_SHA'] = 'a'*40
                with patch.dict(os.environ, env, clear=True), patch('subprocess.run', side_effect=gh):
                    runpy.run_path(str(TOOLS / 'publish-release.py'))
            finally:
                os.chdir(old)
        return calls

    def test_new_release_becomes_latest(self):
        calls = self.publish('v0.3.0-build.1023')
        self.assertIn('--latest=true', calls[-1])

    def test_old_recovery_cannot_downgrade_latest(self):
        calls = self.publish('v0.3.0-build.1025')
        self.assertIn('--latest=false', calls[-1])

    def test_existing_release_is_not_replaced(self):
        self.assertEqual(len(self.publish(existing=True)), 1)

    def test_recovery_tags_original_apk_source(self):
        call = self.publish(recovered=True)[-1]
        self.assertEqual(call[call.index('--target')+1], 'a'*40)

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

if __name__ == '__main__': unittest.main()
