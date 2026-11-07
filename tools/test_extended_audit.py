import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def load(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(name+'.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

sast=load('check-sast');audit=load('dependency-audit');toolchain=load('toolchain-inventory')

class SastTest(unittest.TestCase):
    def test_missing_output_errors_and_any_finding_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'analysis.sarif'
            with self.assertRaises(ValueError):sast.check([])
            valid={'version':'2.1.0','runs':[{'tool':{'driver':{'name':'CodeQL','rules':[{'id':'rule'}]}},'results':[]}]}
            p.write_text(json.dumps(valid));sast.check([p])
            valid['runs'][0]['results']=[{'ruleId':'rule','level':'warning'}];p.write_text(json.dumps(valid))
            with self.assertRaises(ValueError):sast.check([p])
            valid['runs'][0]['results']=[];valid['runs'][0]['invocations']=[{'executionSuccessful':False}];p.write_text(json.dumps(valid))
            with self.assertRaises(ValueError):sast.check([p])
    def test_action_source_commits_are_scanned_and_findings_preserved(self):
        rows=[{'name':'owner/action','version':'a'*40,'purl':'pkg:github/owner/action@'+'a'*40,'commit':'a'*40}]
        calls=[]
        def request(body):calls.append(body);return {'results':[{'vulns':[{'id':'GHSA-fixture'}]}]}
        self.assertEqual(audit.scan(rows,request)[0]['id'],'GHSA-fixture')
        self.assertEqual(calls,[{'queries':[{'commit':'a'*40}]}])
    def test_repository_actions_are_immutable_and_observed_sdk_is_required(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);wf=root/'.github/workflows';wf.mkdir(parents=True)
            (wf/'bad.yml').write_text('steps:\n  - uses: owner/action@main\n')
            with self.assertRaisesRegex(ValueError,'Unpinned'):toolchain.inventory(root,root/'sdk')
            (wf/'bad.yml').write_text('steps:\n  - uses: owner/action@'+'a'*40+'\n')
            wrapper=root/'gradle/wrapper';wrapper.mkdir(parents=True)
            (wrapper/'gradle-wrapper.properties').write_text('distributionUrl=https\\://example/gradle.zip\ndistributionSha256Sum='+'b'*64+'\n')
            with self.assertRaisesRegex(ValueError,'SDK'):toolchain.inventory(root,root/'sdk')
