import hashlib
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

SCRIPT=Path(__file__).with_name('download-baselines.py')


class PublicBaselineTest(unittest.TestCase):
    def download(self, release, bad_oldest=False, corrupt=False):
        calls=[]
        def gh(args,**kwargs):
            calls.append(args)
            if args[1]=='api':
                kwargs['stdout'].write(b'bad' if corrupt else b'APK fixture')
            else:
                directory=Path(args[args.index('--dir')+1]);directory.mkdir(exist_ok=True)
                (directory/args[args.index('--pattern')+1]).write_bytes(b'APK fixture')
        with tempfile.TemporaryDirectory() as temp:
            old=Path.cwd();os.chdir(temp)
            try:
                oldest={'id':423526572,'tag_name':'v0.9.4-build.1085' if bad_oldest else 'v0.9.5-build.1087',
                        'draft':True,'assets':[{'id':675560408,'name':'VK-Music-Export-0.9.5-1087.apk',
                        'digest':'sha256:'+hashlib.sha256(b'APK fixture').hexdigest()}]}
                with patch('subprocess.check_output',side_effect=[json.dumps(release),json.dumps(oldest)]),patch('subprocess.run',side_effect=gh):
                    runpy.run_path(str(SCRIPT.resolve()))
                self.assertEqual(Path('upgrade-input/previous.apk').read_bytes(),b'APK fixture')
                self.assertEqual(Path('upgrade-input/oldest.apk').read_bytes(),b'APK fixture')
            finally:os.chdir(old)
        return calls

    def test_baseline_follows_actual_latest_and_keeps_oldest_separately(self):
        calls=self.download({'tagName':'v0.10.1-build.1201','assets':[{'name':'VK-Music-Export-0.10.1-1201.apk'}]})
        self.assertEqual(calls[0][3],'v0.10.1-build.1201')
        self.assertEqual(calls[1][:3],['gh','api','repos/alcovaibe/vk-music-export/releases/assets/675560408'])

    def test_mislabeled_asset_cannot_stand_in_for_latest(self):
        with self.assertRaisesRegex(AssertionError,'does not match'):
            self.download({'tagName':'v0.10.1-build.1201','assets':[{'name':'VK-Music-Export-0.9.18-1148.apk'}]})

    def test_ambiguous_apks_block_upgrade_verification(self):
        with self.assertRaisesRegex(AssertionError,'ambiguous'):
            self.download({'tagName':'v0.10.1-build.1201','assets':[
                {'name':'VK-Music-Export-0.10.1-1201.apk'},{'name':'VK-Music-Export-0.10.0-1200.apk'}]})

    def test_different_oldest_release_cannot_replace_hidden_baseline(self):
        with self.assertRaisesRegex(AssertionError,'identity changed'):
            self.download({'tagName':'v0.10.1-build.1201','assets':[{'name':'VK-Music-Export-0.10.1-1201.apk'}]},bad_oldest=True)

    def test_hidden_baseline_download_still_checks_digest(self):
        with self.assertRaisesRegex(AssertionError,'digest mismatch'):
            self.download({'tagName':'v0.10.1-build.1201','assets':[{'name':'VK-Music-Export-0.10.1-1201.apk'}]},corrupt=True)
