import copy
import json
from pathlib import Path
import runpy
import unittest

extract = runpy.run_path(str(Path(__file__).with_name('extract-saved-benchmarks.py')))['extract']


class SavedBenchmarkTest(unittest.TestCase):
    def record(self):
        return dict(schema=1, api=29, results=[dict(files=n, first20_ms=1, reopen_ms=1, queue_build_ms=1, write_ms=1, paged_read_ms=2, alias_us=3, pages=(n+99)//100, uri_index=True) for n in [100, 4500, 20000]])

    def text(self, record):
        return 'VK_SAVED_BENCHMARK=' + json.dumps(record)

    def test_complete_measured_workloads_are_accepted(self):
        record=self.record()
        self.assertEqual(record,extract(self.text(record),29))

    def test_missing_duplicate_truncated_and_wrong_api_reports_are_rejected(self):
        text=self.text(self.record())
        for bad in ['',text+'\n'+text,text[:-2]]:
            with self.subTest(bad=bad[:20]),self.assertRaises(ValueError):extract(bad,29)
        with self.assertRaises(ValueError):extract(text,30)

    def test_nonfinite_negative_boolean_and_missing_measurements_are_rejected(self):
        for field in ['write_ms','first20_ms','reopen_ms','queue_build_ms','paged_read_ms','alias_us','pages']:
            for value in [float('nan'),float('inf'),-1,True,None,'1']:
                record=self.record();record['results'][0][field]=value
                with self.subTest(field=field,value=value),self.assertRaises(ValueError):extract(self.text(record),29)

    def test_wrong_workload_schema_index_and_page_counts_are_rejected(self):
        for field,value in [('files',101),('pages',100),('uri_index',False)]:
            record=self.record();record['results'][0][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):extract(self.text(record),29)
        record=self.record();record['schema']=2
        with self.assertRaises(ValueError):extract(self.text(record),29)
