import io
import subprocess
import unittest
from unittest.mock import Mock, patch

from gradle_preflight import PLUGIN, PUBLISHED_VERSION, preflight, retryable


def failure(version=PUBLISHED_VERSION, plugin=PLUGIN):
    return (
        f"Plugin [id: '{plugin}', version: '{version}', apply: false] "
        "was not found in any of the following sources:\n"
        f"(could not resolve plugin artifact '{plugin}:{plugin}.gradle.plugin:{version}')\n"
    )


class GradlePreflightTest(unittest.TestCase):
    def setUp(self):
        self.output = patch("sys.stdout", new_callable=io.StringIO)
        self.output.start()
        self.addCleanup(self.output.stop)

    def test_observed_plugin_resolution_error_is_recognized(self):
        self.assertTrue(retryable(failure(), PUBLISHED_VERSION))

    def test_unknown_versions_plugins_and_failures_are_not_retried(self):
        for output, version in [
            (failure("99.0"), "99.0"),
            (failure("99.0"), PUBLISHED_VERSION),
            (failure(plugin="unknown.plugin"), PUBLISHED_VERSION),
            ("Could not resolve dependencies: HTTP 503", PUBLISHED_VERSION),
        ]:
            with self.subTest(output=output):
                self.assertFalse(retryable(output, version))

    def test_integrity_auth_tls_and_executed_task_failures_fail_immediately(self):
        for error in [
            "Dependency verification failed for configuration ':classpath'",
            "Checksum verification failed", "Expected a SHA256 checksum",
            "Signature verification failed", "PKIX path building failed",
            "SSLHandshakeException", "Received status code 401", "HTTP 403",
            "Unauthorized", "Forbidden", "> Task :compileDebugKotlin FAILED",
            "> Task :testDebugUnitTest FAILED", "> Task :help FAILED",
            "Compilation error", "Script compilation error",
        ]:
            with self.subTest(error=error):
                run = Mock(return_value=subprocess.CompletedProcess([], 1, failure() + error))
                sleep = Mock()
                self.assertEqual(preflight(PUBLISHED_VERSION, run, sleep), 1)
                self.assertEqual(run.call_count, 1)
                sleep.assert_not_called()

    def test_success_runs_only_help_once_with_strict_verification(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 0, "ok\n"))
        sleep = Mock()
        self.assertEqual(preflight(PUBLISHED_VERSION, run, sleep), 0)
        args = run.call_args.args[0]
        self.assertEqual(args, ["./gradlew", "--dependency-verification", "strict",
                                "--console=plain", "help", "--stacktrace", "--info"])
        sleep.assert_not_called()

    def test_recovery_refreshes_metadata_once_and_never_runs_tests(self):
        run = Mock(side_effect=[subprocess.CompletedProcess([], 1, failure()),
                                subprocess.CompletedProcess([], 0, "recovered\n")])
        sleep = Mock()
        self.assertEqual(preflight(PUBLISHED_VERSION, run, sleep), 0)
        sleep.assert_called_once_with(10)
        self.assertEqual(run.call_count, 2)
        first, second = [call.args[0] for call in run.call_args_list]
        self.assertEqual(second, first + ["--refresh-dependencies"])

    def test_persistent_failure_propagates_exit_status_after_two_attempts(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 7, failure()))
        sleep = Mock()
        self.assertEqual(preflight(PUBLISHED_VERSION, run, sleep), 7)
        self.assertEqual(run.call_count, 2)
        sleep.assert_called_once_with(10)

    def test_interrupted_process_does_not_retry(self):
        run = Mock(return_value=subprocess.CompletedProcess([], -15, failure()))
        sleep = Mock()
        self.assertEqual(preflight(PUBLISHED_VERSION, run, sleep), 1)
        sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
