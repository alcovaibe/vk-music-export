import copy
import json
from pathlib import Path
import runpy
import unittest

extract = runpy.run_path(str(Path(__file__).with_name('extract-checkpoint-benchmarks.py')))['extract']


class CheckpointBenchmarkTest(unittest.TestCase):
    def records(self):
        metadata = {'schema': 1, 'api': 29, 'results': []}
        receipts = copy.deepcopy(metadata)
        for n in [100, 4500, 20000]:
            metadata['results'].append(dict(targets=n, snapshot_ms=1, checkpoint_ms=2, checkpoint_p95_ms=.1, restore_ms=1,
                                           target_writes=n, progress_writes=n+1, target_payload_bytes=n*100, progress_payload_bytes=n*100))
            receipts['results'].append(dict(receipts=n, begin_ms=1, save_ms=2, save_p95_ms=.1, reopen_ms=1, read_all_ms=1,
                                           receipt_inserts=n, receipt_updates=0, state_writes=n+1, receipt_text_bytes=n*131,
                                           state_text_bytes=(n+1)*100, observed_wal_size_bytes=4096))
        return metadata, receipts

    def text(self, metadata, receipts):
        return '--------- beginning of main\nVK_METADATA_BENCHMARK=' + json.dumps(metadata) + '\nVK_RECEIPT_BENCHMARK=' + json.dumps(receipts)

    def test_both_complete_reports_are_accepted(self):
        m, r = self.records()
        self.assertEqual(extract(self.text(m, r), 29), {'metadata': m, 'receipts': r})

    def test_missing_duplicate_and_truncated_reports_are_rejected(self):
        m, r = self.records()
        text = self.text(m, r)
        for bad in [text.split('VK_RECEIPT_BENCHMARK=')[0], text+'\nVK_RECEIPT_BENCHMARK='+json.dumps(r), text[:-2]]:
            with self.subTest(text=bad[:50]), self.assertRaises(ValueError):
                extract(bad, 29)

    def test_wrong_api_schema_and_workload_are_rejected(self):
        for field, value in [('api', 30), ('schema', 2), ('results', [])]:
            m, r = self.records()
            r[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                extract(self.text(m, r), 29)

    def test_nonfinite_negative_missing_and_nonnumeric_measurements_are_rejected(self):
        for value in [float('nan'), float('inf'), -1, None, True, '0.1']:
            for kind, field in [(0, 'checkpoint_p95_ms'), (1, 'save_p95_ms')]:
                records = self.records()
                records[kind]['results'][0][field] = value
                with self.subTest(value=value, field=field), self.assertRaises(ValueError):
                    extract(self.text(*records), 29)

    def test_changed_write_counts_cannot_pass_as_linear_writes(self):
        for kind, field in [(0, 'target_writes'), (0, 'progress_writes'), (1, 'receipt_inserts'), (1, 'receipt_updates'), (1, 'state_writes')]:
            records = self.records()
            records[kind]['results'][0][field] += 1
            with self.subTest(field=field), self.assertRaises(ValueError):
                extract(self.text(*records), 29)
