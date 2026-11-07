import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('r8',Path(__file__).with_name('verify-r8-evidence.py'))
r8=importlib.util.module_from_spec(spec);spec.loader.exec_module(r8)

class R8EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.mapping=self.root/'app/build/outputs/mapping/release/mapping.txt';self.mapping.parent.mkdir(parents=True);self.mapping.write_text('mapping\n'*50)
        self.report=self.root/'app/build/outputs/androidTest-results/connected/TEST-r8.xml';self.report.parent.mkdir(parents=True)
        self.report.write_text('<testsuite><testcase classname="app.alcovaibe.vkmusicexport.R8PlaybackDeviceTest" name="minifiedOfflinePlaybackPauseStopResumeAndBackgroundProduceMeasuredEvidence"/></testsuite>')
        self.log=self.root/'r8-evidence/playback.log';self.log.parent.mkdir()
        self.row=dict(schema=1,api=29,debuggable=False,first_play_ms=20.5,cpu_ms=19,pss_kib=100,java_heap_bytes=1024,background_sample_ms=5000,pause_seek_resume=True,stop_new_play=True)
        self.write()
    def write(self):self.log.write_text('VK_R8_PLAYBACK='+json.dumps(self.row)+'\n')
    def test_exact_measurements_are_required(self):self.assertEqual(r8.verify(self.root,29),self.row)
    def test_install_or_debug_build_is_insufficient(self):
        self.mapping.unlink()
        with self.assertRaisesRegex(ValueError,'mapping'):r8.verify(self.root,29)
    def test_skipped_playback_is_rejected(self):
        self.report.write_text(self.report.read_text().replace('/>','><skipped/></testcase>'))
        with self.assertRaises(ValueError):r8.verify(self.root,29)
    def test_wrong_api_debuggable_missing_or_invalid_sample_is_rejected(self):
        for key,value in [('api',36),('debuggable',True),('first_play_ms',float('nan')),('stop_new_play',False),('background_sample_ms',0)]:
            original=self.row[key];self.row[key]=value;self.write()
            with self.assertRaises(ValueError):r8.verify(self.root,29)
            self.row[key]=original
