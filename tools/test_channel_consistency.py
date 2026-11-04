import copy
import hashlib
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('channel_consistency', Path(__file__).with_name('check-update-channel.py'))
channel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(channel)


class ChannelConsistencyTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            'schemaVersion': 1, 'platform': 'android', 'channel': 'stable',
            'applicationId': channel.PACKAGE,
            'release': {'versionName': '0.12.1', 'versionCode': 1433, 'minSdk': 29,
                        'tag': 'v0.12.1-build.1433', 'apk': 'VK-Music-Export-0.12.1-1433.apk',
                        'size': 7, 'sha256': hashlib.sha256(b'fixture').hexdigest()}}
        item = self.manifest['release']
        self.latest = {'tag_name': item['tag'], 'draft': False, 'prerelease': False,
                       'assets': [{'name': item['apk'], 'state': 'uploaded', 'size': item['size'],
                                   'digest': 'sha256:' + item['sha256']}]}

    def test_exact_published_metadata_matches(self):
        self.assertEqual(channel.check_latest(self.manifest, self.latest), 'v0.12.1-build.1433')

    def test_stale_or_future_channel_is_not_reported_as_delivered(self):
        for tag in ('v0.12.0-build.1343', 'v0.12.2-build.1434'):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, 'differs from Latest'):
                channel.check_latest(self.manifest, dict(self.latest, tag_name=tag))

    def test_asset_replacement_incomplete_upload_and_duplicate_fail(self):
        for changes in ({'size': 8}, {'digest': 'sha256:' + '0' * 64},
                        {'digest': None}, {'state': 'new'}, {'name': 'another.apk'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                channel.check_latest(self.manifest, dict(self.latest, assets=[dict(self.latest['assets'][0], **changes)]))
        for assets in ([], self.latest['assets'] * 2):
            with self.subTest(assets=assets), self.assertRaises(ValueError):
                channel.check_latest(self.manifest, dict(self.latest, assets=assets))

    def test_rc_and_draft_cannot_be_delivered_as_stable(self):
        for key in ('draft', 'prerelease'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                channel.check_latest(self.manifest, dict(self.latest, **{key: True}))

    def test_invalid_manifest_is_rejected_offline(self):
        for key, value in (('versionCode', True), ('versionCode', 0), ('minSdk', 0),
                           ('size', 100 * 1024 * 1024 + 1), ('sha256', 'invalid'),
                           ('apk', '../app.apk'), ('versionName', 'invalid'), ('tag', 'v0.12.0-build.1433')):
            candidate = copy.deepcopy(self.manifest)
            candidate['release'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                channel.validate_manifest(candidate)
        for key, value in (('applicationId', 'another.package'), ('channel', 'rc'),
                           ('platform', 'ios'), ('schemaVersion', 2)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                channel.validate_manifest(dict(self.manifest, **{key: value}))

    def test_cli_refuses_latest_movement_and_removes_previous_success_receipt(self):
        import json
        import tempfile
        from unittest.mock import patch
        self.latest.update(id=17,target_commitish='a'*40)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); manifest=root/'candidate.json';manifest.write_text(json.dumps(self.manifest))
            receipt=root/'channel-verification.json';receipt.write_text('{"state":"old_success"}')
            changed=dict(self.latest,id=18)
            with patch('sys.argv',['check','--manifest',str(manifest),'--output',directory]),patch.object(channel,'api',side_effect=[self.latest,changed]):
                with self.assertRaisesRegex(ValueError,'Latest changed'): channel.main()
            self.assertFalse(receipt.exists())

    def test_live_retry_waits_for_cdn_and_checks_raw_pointer_again(self):
        import tempfile
        from unittest.mock import patch
        self.latest.update(id=17,target_commitish='a'*40)
        with tempfile.TemporaryDirectory() as directory:
            with patch('sys.argv',['check','--public-retries','2','--output',directory]),patch.object(channel,'public_manifest',side_effect=[ValueError('stale CDN'),self.manifest,self.manifest]),patch.object(channel,'api',return_value=self.latest),patch.object(channel.time,'sleep') as delay:
                channel.main();delay.assert_called_once_with(10)
            import json
            self.assertEqual(json.loads((Path(directory)/'channel-verification.json').read_text())['state'],'public_channel_verified')
