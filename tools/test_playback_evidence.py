import json
from pathlib import Path
import runpy
import tempfile
import unittest

extract=runpy.run_path(str(Path(__file__).with_name('extract-playback-benchmarks.py')))['extract']
verify=runpy.run_path(str(Path(__file__).with_name('verify-playback-resumption.py')))['verify']


class PlaybackEvidenceTest(unittest.TestCase):
    def record(self):
        return dict(schema=1,api=29,results=[dict(items=n,install_ms=1,restore_ms=2,updates_ms=3,cursor_writes=100,queue_rewrites=0) for n in [100,4500,20000]])
    def text(self,record):return 'VK_PLAYBACK_BENCHMARK='+json.dumps(record)
    def test_measured_complete_workloads_are_required(self):
        raw=self.text(self.record());self.assertEqual(extract(raw,29),self.record())
        for bad in ['',raw+'\n'+raw,raw[:-1]]:
            with self.subTest(bad=bad[:12]),self.assertRaises(ValueError):extract(bad,29)
    def test_api_workload_and_costs_cannot_be_substituted(self):
        for field,value in [('schema',2),('api',30),('results',[])]:
            record=self.record();record[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):extract(self.text(record),29)
        for field in ['install_ms','restore_ms','updates_ms']:
            for value in [-1,float('nan'),float('inf'),None,True,'1']:
                record=self.record();record['results'][0][field]=value
                with self.subTest(field=field,value=value),self.assertRaises(ValueError):extract(self.text(record),29)
    def test_cursor_updates_must_not_rewrite_the_queue(self):
        for field,value in [('cursor_writes',99),('cursor_writes',True),('queue_rewrites',1),('queue_rewrites',False)]:
            record=self.record();record['results'][0][field]=value
            with self.subTest(field=field,value=value),self.assertRaises(ValueError):extract(self.text(record),29)
    def fixture(self,root):
        for phase in ['seed','resume','active','cleared']:(root/f'{phase}.txt').write_text('OK (1 test)\n')
        proof=dict(api=29,paused_position=321,seed_pid=100,resume_pid=101,active_pid=102,cleared_pid=103,order=[2,0,3,1],
                   media_session_resumption=True,paused_restore=True,active_restore=True,explicit_stop=True,cleared_after_restart=True)
        (root/'proof.json').write_text(json.dumps(proof));return proof
    def test_four_processes_and_all_real_phases_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);proof=self.fixture(root);self.assertEqual(proof,verify(root,29))
            for phase in ['seed','resume','active','cleared']:
                (root/f'{phase}.txt').write_text('FAILURES!!!\nOK (1 test)')
                with self.subTest(phase=phase),self.assertRaises(ValueError):verify(root,29)
                self.fixture(root)
    def test_reused_process_wrong_position_and_incomplete_proofs_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for field,value in [('api',30),('resume_pid',100),('seed_pid',True),('paused_position',0),('order',[0,1,2,2]),
                                ('active_restore',False),('explicit_stop',None),('media_session_resumption',False)]:
                proof=self.fixture(root);proof[field]=value;(root/'proof.json').write_text(json.dumps(proof))
                with self.subTest(field=field),self.assertRaises(ValueError):verify(root,29)


if __name__=='__main__':unittest.main()
