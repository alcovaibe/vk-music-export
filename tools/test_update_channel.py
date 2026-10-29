import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from update_channel import channel_manifest, publish_channel, PACKAGE


class UpdateChannelTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / 'fixture.apk'
        self.apk.write_bytes(b'checked APK fixture')
        self.info = {'applicationId': PACKAGE, 'versionName': '0.9.4', 'versionCode': 1081,
                     'minSdk': 36, 'tag': 'v0.9.4-build.1081', 'apk': 'VK-Music-Export-0.9.4-1081.apk'}
        self.release = {'tag_name': self.info['tag'], 'draft': False, 'prerelease': False,
                        'assets': [{'name': self.info['apk'], 'state': 'uploaded',
                                    'size': self.apk.stat().st_size,
                                    'digest': 'sha256:' + hashlib.sha256(self.apk.read_bytes()).hexdigest()}]}

    def test_manifest_is_bound_to_uploaded_apk(self):
        m = channel_manifest(self.info, self.release, self.apk)
        self.assertEqual(m['release']['sha256'], self.release['assets'][0]['digest'][7:])
        self.assertEqual(m['release']['versionCode'], 1081)
        self.release['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with self.assertRaises(AssertionError):
            channel_manifest(self.info, self.release, self.apk)

    def test_unpublished_prerelease_duplicate_or_missing_apk_is_rejected(self):
        for changes in ({'draft': True}, {'prerelease': True}, {'assets': []},
                        {'assets': self.release['assets'] * 2}):
            with self.subTest(changes=changes), self.assertRaises(AssertionError):
                channel_manifest(self.info, dict(self.release, **changes), self.apk)

    def test_newer_latest_prevents_channel_rollback(self):
        with patch('update_channel.api', return_value={'tag_name': 'v0.9.5-build.1082'}), patch('subprocess.run') as write:
            publish_channel(self.info, self.apk)
            write.assert_not_called()

    def test_channel_update_uses_reviewable_branch_and_never_writes_main(self):
        old = channel_manifest(self.info, self.release, self.apk)
        old['release']['versionCode'] = 1080
        existing = {'sha': 'file-sha', 'content': base64.b64encode(json.dumps(old).encode()).decode()}
        with patch('update_channel.api', side_effect=[self.release, existing, [], {'object':{'sha':'base-sha'}}]), patch('subprocess.run') as write:
            publish_channel(self.info, self.apk)
            payload = json.loads(next(c.kwargs['input'] for c in write.call_args_list if 'PUT' in c.args[0]))
            self.assertEqual(payload['sha'], 'file-sha')
            self.assertEqual(payload['branch'], 'release/update-channel-1081')
            self.assertIn('create', write.call_args.args[0])
            self.assertIn('--body-file', write.call_args.args[0])
            decoded = json.loads(base64.b64decode(payload['content']))
            self.assertEqual(decoded['release']['versionCode'], 1081)

    def test_same_code_cannot_replace_an_existing_artifact(self):
        old = channel_manifest(self.info, self.release, self.apk)
        old['release']['sha256'] = '0' * 64
        existing = {'sha': 'file-sha', 'content': base64.b64encode(json.dumps(old).encode()).decode()}
        with patch('update_channel.api', side_effect=[self.release, existing]), patch('subprocess.run') as write:
            with self.assertRaises(AssertionError):
                publish_channel(self.info, self.apk)
            write.assert_not_called()
