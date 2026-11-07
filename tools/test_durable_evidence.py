import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from release_evidence import bundle, CORE

spec=importlib.util.spec_from_file_location('durable',Path(__file__).with_name('verify-release-evidence.py'))
durable=importlib.util.module_from_spec(spec);spec.loader.exec_module(durable)

class DurableEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.apk=self.root/'fixture.apk';self.apk.write_bytes(b'APK fixture')
        self.info={'commit':'a'*40,'tag':'v0.1-build.1','versionCode':1,'apk':self.apk.name}
        for name in CORE:(self.root/name).write_text('public fixture')
        (self.root/'build-info.json').write_text(json.dumps(self.info))
        (self.root/'SHA256SUMS.txt').write_text(hashlib.sha256(self.apk.read_bytes()).hexdigest()+'  '+self.apk.name+'\n')
        (self.root/'upgrade-check.txt').write_text('PASS: synthetic fixture\n')
        (self.root/'sbom.cdx.json').write_text(json.dumps({'bomFormat':'CycloneDX','components':[{'name':'fixture'}]}))
        (self.root/'osv.json').write_text(json.dumps({'findings':[]}))
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):self.archive=bundle(self.info,self.apk,self.root,self.root/'ci',self.root/'upgrades')
    def test_old_archive_needs_no_ci_admin_and_exact_apk_binding_is_checked(self):
        os.utime(self.archive,(1_000_000_000,1_000_000_000))
        result=durable.verify(self.archive,self.apk,'a'*40)
        self.assertEqual(result['source_commit'],'a'*40)
        self.apk.write_bytes(b'different APK')
        with self.assertRaisesRegex(ValueError,'APK differs'):durable.verify(self.archive,self.apk)
    def test_changed_member_and_unexpected_source_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'source'):durable.verify(self.archive,expected_commit='b'*40)
        changed=self.root/'changed.zip'
        with zipfile.ZipFile(self.archive) as src,zipfile.ZipFile(changed,'w') as dst:
            for item in src.infolist():dst.writestr(item,b'changed' if item.filename=='release/NOTICE' else src.read(item))
        with self.assertRaisesRegex(ValueError,'content differs'):durable.verify(changed)
    def test_extra_traversal_and_duplicate_entries_are_rejected_without_extraction(self):
        for extra in ('../outside.txt','extra.txt','release/NOTICE'):
            changed=self.root/'changed.zip'
            with zipfile.ZipFile(self.archive) as src,zipfile.ZipFile(changed,'w') as dst:
                for item in src.infolist():dst.writestr(item,src.read(item))
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore');dst.writestr(extra,b'never extract')
            with self.assertRaises(ValueError):durable.verify(changed)
