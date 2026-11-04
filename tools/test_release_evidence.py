import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from release_evidence import bundle, CORE

class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.apk=self.root/'fixture.apk';self.apk.write_bytes(b'public APK fixture')
        for name in CORE: (self.root/name).write_text('public evidence fixture')
        self.info={'tag':'v0.12.1-build.1433','commit':'a'*40,'versionCode':1433}
        p=patch.dict(os.environ,{'GITHUB_ACTIONS':'false'});p.start();self.addCleanup(p.stop)
    def build(self): return bundle(self.info,self.apk,self.root,self.root/'ci',self.root/'upgrades')
    def test_manifest_binds_every_file_and_exact_apk_without_exporting_binary_or_keys(self):
        (self.root/'private.jks').write_text('NEVER EXPORT')
        with zipfile.ZipFile(self.build()) as z:
            m=json.loads(z.read('evidence-manifest.json'))
            self.assertEqual(m['apk']['sha256'],hashlib.sha256(self.apk.read_bytes()).hexdigest())
            self.assertNotEqual(m['manual_acceptance'],'passed_for_exact_apk')
            for name,data in m['files'].items(): self.assertEqual(hashlib.sha256(z.read(name)).hexdigest(),data['sha256'])
            self.assertFalse(any(name.endswith(('.apk','.jks')) for name in z.namelist()))
    def test_missing_evidence_does_not_publish_incomplete_archive(self):
        (self.root/'osv.json').unlink()
        with self.assertRaises(ValueError): self.build()
        self.assertFalse((self.root/'release-evidence.zip').exists())
    def test_ci_requires_all_nine_interface_and_updater_reports(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}),self.assertRaisesRegex(ValueError,'API 29'): self.build()
    def test_symlink_is_never_archived(self):
        (self.root/'ci').mkdir();(self.root/'ci/link.txt').symlink_to(self.apk)
        with self.assertRaisesRegex(ValueError,'Symlink'): self.build()
    def test_complete_ci_bundle_checks_each_updater_subject_and_preserves_raw_reports(self):
        manifest={'schemaVersion':1,'platform':'android','channel':'stable','applicationId':'app.alcovaibe.vkmusicexport',
            'release':{'versionName':'0.12.1','versionCode':1433,'minSdk':29,'tag':'v0.12.1-build.1433',
            'apk':'VK-Music-Export-0.12.1-1433.apk','size':7,'sha256':'a'*64}}
        ci=self.root/'ci';fixtures=ci/'Update-channel-fixtures';fixtures.mkdir(parents=True)
        (fixtures/'channel-verification.json').write_text(json.dumps({'state':'candidate_verified','manifest':manifest,
            'apk_verification':{'sha256':'a'*64,'versionCode':1433}}))
        for api in range(29,38):
            directory=ci/f'Android-interface-API-{api}';directory.mkdir()
            (directory/'report.xml').write_text('<testsuite tests="1" failures="0"/>')
            directory=ci/f'Update-delivery-API-{api}';directory.mkdir()
            (directory/'result.json').write_text(json.dumps({'api':api,'to':1433,'sha256':'a'*64,
                'system_installer':True,'private_marker_preserved':True}))
        (ci/'Android-reports').mkdir();(ci/'Android-reports/unit.xml').write_text('<testsuite/>')
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}):
            with zipfile.ZipFile(self.build()) as z: self.assertIn('ci/Update-delivery-API-37/result.json',z.namelist())
            proof=ci/'Update-delivery-API-37/result.json';value=json.loads(proof.read_text());value['sha256']='b'*64;proof.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError,'Inconsistent updater evidence API 37'): self.build()
