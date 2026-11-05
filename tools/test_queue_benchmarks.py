import json
from pathlib import Path
import runpy
import unittest

extract=runpy.run_path(str(Path(__file__).with_name('extract-queue-benchmarks.py')))['extract']


class QueueBenchmarkTest(unittest.TestCase):
    def record(self):
        return dict(schema=1,api=29,results=[dict(tasks=n,snapshot_queries=3,page_queries=5,report_queries=1,snapshot_ms=.1,page_ms=.2,report_cursor_ms=1,reopen_ms=.3) for n in [100,4500,20000]])
    def text(self,record):return 'VK_QUEUE_BENCHMARK='+json.dumps(record)
    def test_complete_bounded_measurements_are_accepted(self):
        r=self.record();self.assertEqual(r,extract(self.text(r),29))
    def test_missing_duplicate_and_truncated_records_are_rejected(self):
        text=self.text(self.record())
        for bad in ['',text+'\n'+text,text[:-2]]:
            with self.subTest(bad=bad[:10]),self.assertRaises(ValueError):extract(bad,29)
    def test_schema_api_and_missing_workloads_are_rejected(self):
        for field,value in [('schema',2),('api',30),('results',[])]:
            r=self.record();r[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):extract(self.text(r),29)
    def test_nonfinite_negative_and_missing_measurements_are_rejected(self):
        for field in ['snapshot_ms','page_ms','report_cursor_ms','reopen_ms']:
            for value in [float('nan'),float('inf'),-1,None,True,'1']:
                r=self.record();r['results'][0][field]=value
                with self.subTest(field=field,value=value),self.assertRaises(ValueError):extract(self.text(r),29)
    def test_n_plus_one_and_noninteger_query_counts_are_rejected(self):
        for field,value in [('snapshot_queries',5),('page_queries',20002),('report_queries',20000),('snapshot_queries',True),('page_queries',1.5)]:
            r=self.record();r['results'][0][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):extract(self.text(r),29)
