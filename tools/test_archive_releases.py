import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec=importlib.util.spec_from_file_location('archive_releases',Path(__file__).with_name('archive-releases.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class ArchiveTest(unittest.TestCase):
    def release(self,number,tag):
        return {'id':number,'tag_name':tag,'target_commitish':'a'*40,'draft':False,'immutable':False,
                'assets':[{'id':number*10,'name':tag+'.apk','size':42,'digest':'sha256:fixture'}]}

    def run_archive(self,apply,artifact=False,break_assets=False):
        rows=[self.release(1,'v0.9.14-build.1126'),self.release(2,'v0.9.15-build.1130'),self.release(3,'v0.10.0-build.1192')]
        backend={r['id']:copy.deepcopy(r) for r in rows}
        mutations=[];uploaded=[]
        def api(path,method='GET',data=None):
            if path.endswith('/latest'):return copy.deepcopy(backend[3])
            if '/releases/' in path:
                row=backend[int(path.rsplit('/',1)[1])]
                if method=='PATCH':
                    mutations.append(('draft',row['id']));row.update(data)
                    if break_assets:row['assets']=[]
                return copy.deepcopy(row)
            if '/actions/artifacts/' in path and method=='DELETE':
                self.assertTrue(uploaded,'Never remove the public artifact before preserving its bytes privately')
                mutations.append(('artifact',int(path.rsplit('/',1)[1])))
                return None
            raise AssertionError(path)
        def pages(path,key=None):
            return copy.deepcopy(rows) if key is None else ([{'id':9,'name':'Old APK','expired':False,'workflow_run':{'head_sha':'b'*40}}] if artifact else [])
        def download(path,file):
            with zipfile.ZipFile(file,'w') as archive:archive.writestr('app.apk',b'APK fixture')
        def upload(release_id,name,file):
            self.assertTrue(backend[release_id]['draft'])
            value={'id':99,'name':name,'state':'uploaded','size':file.stat().st_size,
                   'digest':'sha256:'+hashlib.sha256(file.read_bytes()).hexdigest()}
            backend[release_id]['assets'].append(value);uploaded.append(value)
            return value
        with tempfile.TemporaryDirectory() as temp:
            old=Path.cwd();os.chdir(temp)
            try:
                with patch.dict(os.environ,{'GH_REPO':module.REPO}),patch.object(module,'api',side_effect=api),\
                     patch.object(module,'pages',side_effect=pages),patch.object(module,'tag_object',return_value={'type':'commit','sha':'a'*40}),\
                     patch.object(module,'source_version',return_value=(0,9,14)),patch.object(module,'download',side_effect=download),patch.object(module,'upload',side_effect=upload):
                    module.main(apply)
                report=json.loads(Path('release-archive-audit/report.json').read_text())
                self.assertEqual(report['status'],'passed')
            finally:os.chdir(old)
        self.assertFalse(backend[2]['draft']);self.assertFalse(backend[3]['draft'])
        return backend,mutations

    def test_cutoff_excludes_0915_and_compares_numbers(self):
        rows=[self.release(i,tag) for i,tag in enumerate(['v0.9.14-build.1','v0.9.15-build.2','v0.10.0-build.3','v0.8.4-build.4','unrelated'])]
        self.assertEqual([r['tag_name'] for r in module.selected(rows)],['v0.9.14-build.1','v0.8.4-build.4'])

    def test_plan_never_changes_release_or_artifact(self):
        backend,mutations=self.run_archive(False,artifact=True)
        self.assertFalse(backend[1]['draft']);self.assertEqual(mutations,[])

    def test_only_older_release_becomes_draft(self):
        backend,mutations=self.run_archive(True)
        self.assertTrue(backend[1]['draft']);self.assertEqual(mutations,[('draft',1)])

    def test_original_asset_change_stops_archiving(self):
        with self.assertRaisesRegex(AssertionError,'assets changed'):self.run_archive(True,break_assets=True)

    def test_apk_artifact_is_preserved_privately_before_removal(self):
        backend,mutations=self.run_archive(True,artifact=True)
        self.assertEqual(mutations,[('draft',1),('artifact',9)])
        self.assertEqual(backend[1]['assets'][0]['id'],10)
        self.assertEqual(backend[1]['assets'][-1]['name'],'actions-artifact-9.zip')
