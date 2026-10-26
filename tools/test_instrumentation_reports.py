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
        cases = ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.InterfaceTest" name="case{i}"/>' for i in range(30))
        return f'<testsuites tests="30" failures="0" errors="0" skipped="0"><testsuite>{cases}</testsuite></testsuites>'

    def test_successful_full_suite_is_accepted(self):
        self.assertEqual(self.check([self.successful_report()], '0'), 30)

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
            self.check([report] * 30)

    def test_missing_interface_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.InterfaceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required interface'):
            self.check([report])


if __name__ == '__main__':
    unittest.main()
