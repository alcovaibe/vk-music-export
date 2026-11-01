from pathlib import Path
import tempfile
import unittest

from apk_manifest import configured_min_sdk, release_manifest


class ReleaseManifestTest(unittest.TestCase):
    def manifest(self, sdk=34, package='app.alcovaibe.vkmusicexport', code=1300):
        return f"package: name='{package}' versionCode='{code}' versionName='0.12.0'\nsdkVersion:'{sdk}'\n"

    def test_minimum_is_read_from_the_build_configuration(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'build.gradle.kts'
            for sdk in (34, 36):
                file.write_text(f'android {{\n    minSdk = {sdk}\n}}\n')
                self.assertEqual(configured_min_sdk(file), sdk)
            for text in ('', 'minSdk = 34\nminSdk = 36\n', 'minSdk = value\n', 'minSdk = 0\n'):
                file.write_text(text)
                with self.assertRaises(ValueError):
                    configured_min_sdk(file)

    def test_android14_candidate_and_android16_upgrade_baseline_are_both_read_correctly(self):
        previous = release_manifest(self.manifest(36, code=1279))
        current = release_manifest(self.manifest(34), 34)
        self.assertEqual(previous['minSdk'], 36)
        self.assertEqual(current, dict(applicationId='app.alcovaibe.vkmusicexport', versionCode=1300,
                                       versionName='0.12.0', minSdk=34))

    def test_mislabelled_apk_does_not_claim_android14_support(self):
        with self.assertRaisesRegex(ValueError, 'differs from build configuration'):
            release_manifest(self.manifest(36), 34)

    def test_missing_or_duplicate_sdk_and_debug_or_other_package_are_rejected(self):
        for text in (self.manifest().replace("sdkVersion:'34'\n", ''),
                     self.manifest() + "sdkVersion:'36'\n", self.manifest(0),
                     self.manifest(package='other.package'), self.manifest() + 'application-debuggable\n',
                     self.manifest(code=0)):
            with self.subTest(text=text), self.assertRaises(ValueError):
                release_manifest(text)
