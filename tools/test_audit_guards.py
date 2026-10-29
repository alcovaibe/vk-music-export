import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from release_gate import SCENARIOS, validate_acceptance
from release_notes import notes_for

spec = importlib.util.spec_from_file_location('dependency_audit', Path(__file__).with_name('dependency-audit.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class ReleaseAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name)/'candidate.apk'; self.apk.write_bytes(b'test fixture')
        self.info = {'commit':'a'*40,'versionCode':1149}
        self.evidence = {'status':'passed','source_commit':'a'*40,'version_code':1149,
            'apk_sha256':hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            'tester':'CI fixture','tested_at':'2026-10-29','candidate_run':'123456','device':'Test device',
            'android_version':'Android 16','scenarios':{s:{'status':'passed','evidence':'Synthetic evidence for a unit test only.'} for s in SCENARIOS}}

    def test_pending_manual_acceptance_blocks_stable(self):
        self.evidence['status']='pending'
        with self.assertRaises(ValueError): validate_acceptance(self.evidence,self.info,self.apk)

    def test_rebuilt_or_changed_apk_cannot_reuse_acceptance(self):
        self.apk.write_bytes(b'another build')
        with self.assertRaises(ValueError): validate_acceptance(self.evidence,self.info,self.apk)

    def test_missing_one_manual_scenario_blocks_stable(self):
        self.evidence['scenarios'].pop(SCENARIOS[-1])
        with self.assertRaises(ValueError): validate_acceptance(self.evidence,self.info,self.apk)

    def test_complete_exact_acceptance_is_accepted(self):
        validate_acceptance(self.evidence,self.info,self.apk)

    def test_missing_version_notes_block_release(self):
        path=Path(self.tmp.name)/'CHANGELOG.md';path.write_text('## [0.1.0]\n\nOnly old fixes.\n')
        with self.assertRaises(ValueError): notes_for('0.2.0',path)
        self.assertEqual(notes_for('0.1.0',path),'Only old fixes.')


class DependencyAuditTest(unittest.TestCase):
    def setUp(self): self.items=audit.components('group:library:1.0\n')

    def test_empty_inventory_is_not_clean_scan(self):
        with self.assertRaises(ValueError): audit.components('')

    def test_incomplete_server_response_fails_closed(self):
        with self.assertRaises(ValueError): audit.scan(self.items,lambda _: {})

    def test_network_failure_is_not_clean_scan(self):
        def fail(_): raise OSError('offline')
        with self.assertRaises(OSError): audit.scan(self.items,fail)

    def test_all_pages_of_each_dependency_are_scanned(self):
        requests=[]
        def query(value):
            requests.append(value)
            return {'results':[{'next_page_token':'next'} if len(requests)==1 else {'vulns':[{'id':'GHSA-test'}]}]}
        self.assertEqual(audit.scan(self.items,query)[0]['id'],'GHSA-test')
        self.assertEqual(requests[1]['queries'][0]['page_token'],'next')

    def test_repeated_pagination_never_reports_clean(self):
        with self.assertRaises(ValueError): audit.scan(self.items,lambda _: {'results':[{'next_page_token':'loop'}]})
