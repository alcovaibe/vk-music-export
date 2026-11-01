from pathlib import Path
import runpy
import tempfile
import unittest


verify = runpy.run_path(str(Path(__file__).with_name('verify-instrumentation.py')))['verify']


class InstrumentationReportTest(unittest.TestCase):
    def check(self, reports, exit_code=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, report in enumerate(reports):
                (root / f'TEST-{index}.xml').write_text(report)
            if exit_code is not None:
                (root / 'test-result-exit-code.txt').write_text(exit_code)
            return verify(root)

    def successful_report(self):
        cases = ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.InterfaceTest" name="case{i}"/>' for i in range(74))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.AuditRegressionDeviceTest" name="audit{i}"/>' for i in range(9))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.AccessRecoveryDeviceTest" name="access{i}"/>' for i in range(2))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.MediaFinalizationDeviceTest" name="finalization{i}"/>' for i in range(4))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.DownloadContinuityDeviceTest" name="continuity{i}"/>' for i in range(4))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.LargeAudioAndReuseDeviceTest" name="reuse{i}"/>' for i in range(10))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.TrackMetadataDeviceTest" name="metadata{i}"/>' for i in range(7))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.TrackMetadataUiDeviceTest" name="metadataUi{i}"/>' for i in range(3))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.MusicLibraryRedesignDeviceTest" name="redesign{i}"/>' for i in range(3))
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MusicArtistLoadingDeviceTest" name="timeout"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.TrackMetadataLookupViewModelDeviceTest" name="workflow"/>'
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.DuplicateResumeDeviceTest" name="completeRepeat{i}"/>' for i in range(2))
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateRemovalViewModelDeviceTest" name="workflow"/>'
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.MaintenanceBackgroundDeviceTest" name="background{i}"/>' for i in range(2))
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.ArtistFailureDeviceTest" name="diagnostics"/>'
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.FriendsMusicDeviceTest" name="friends{i}"/>' for i in range(3))
        return f'<testsuites tests="127" failures="0" errors="0" skipped="0"><testsuite>{cases}</testsuite></testsuites>'

    def test_successful_full_suite_is_accepted(self):
        self.assertEqual(self.check([self.successful_report()], '0'), 127)

    def test_missing_report_after_installer_failure_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'No JUnit report'):
            self.check([])

    def test_runner_exit_code_is_checked_even_with_an_old_success_report(self):
        with self.assertRaisesRegex(ValueError, 'non-zero exit code'):
            self.check([self.successful_report()], '1')

    def test_zero_tests_is_not_success(self):
        with self.assertRaisesRegex(ValueError, 'Only 0'):
            self.check(['<testsuites tests="0"/>'])

    def test_failures_errors_and_skips_block_publication(self):
        for counter in ('failures', 'errors', 'skipped'):
            with self.subTest(counter=counter), self.assertRaisesRegex(ValueError, counter):
                self.check([f'<testsuites {counter}="1"/>'])

    def test_testcase_failure_is_not_hidden_by_summary_counters(self):
        with self.assertRaisesRegex(ValueError, 'failed or was skipped'):
            self.check(['<testsuite><testcase classname="C" name="n"><failure/></testcase></testsuite>'])

    def test_duplicate_reports_do_not_inflate_coverage(self):
        report = '<testsuite><testcase classname="C" name="n"/></testsuite>'
        with self.assertRaisesRegex(ValueError, 'Only 1'):
            self.check([report] * 72)

    def test_missing_interface_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.InterfaceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required interface'):
            self.check([report])

    def test_missing_audit_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.AuditRegressionDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required audit'):
            self.check([report])

    def test_missing_media_finalization_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.MediaFinalizationDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required media finalization'):
            self.check([report])


    def test_missing_download_continuity_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.DownloadContinuityDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required download continuity'):
            self.check([report])

    def test_missing_large_audio_and_reuse_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.LargeAudioAndReuseDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required large audio and reuse'):
            self.check([report])

    def test_missing_metadata_coverage_is_rejected(self):
        for name, reason in [('TrackMetadataDeviceTest', 'Required track metadata tests'),
                             ('TrackMetadataUiDeviceTest', 'Required track metadata interface')]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, reason):
                self.check([self.successful_report().replace(name, 'OtherTest')])


if __name__ == '__main__':
    unittest.main()
