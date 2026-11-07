import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('performance',Path(__file__).with_name('summarize-r8-performance.py'))
performance=importlib.util.module_from_spec(spec);spec.loader.exec_module(performance)

class PerformanceSummaryTest(unittest.TestCase):
    def test_real_sample_counts_and_missing_or_incomplete_samples(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for mode in ('cold','warm'):
                for i in range(1,6):(root/f'{mode}-{i}.txt').write_text(f'Status: ok\nTotalTime: {100*i}\n')
            (root/'frames.txt').write_text('Flags,IntendedVsync,FrameCompleted\n0,1000000,17000000\n0,2000000,34000000\n1,1,2\n')
            result=performance.summarize(root,29)
            self.assertEqual(result['startup']['cold_total_ms']['p50'],300)
            self.assertEqual(result['frame_ms']['samples'],2);self.assertEqual(result['discarded_frames'],1)
            (root/'warm-5.txt').write_text('Error: not installed\n')
            with self.assertRaises(ValueError):performance.summarize(root,29)
    def test_no_frames_cannot_look_like_zero_latency(self):
        with self.assertRaises(ValueError):performance.percentiles([])
