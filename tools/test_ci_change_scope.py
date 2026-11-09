import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("scope", Path(__file__).with_name("ci-change-scope.py"))
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class AndroidScopeTest(unittest.TestCase):
    def test_markdown_only_push_and_pr(self):
        for event, ref in [("push", "refs/heads/main"), ("push", "refs/heads/fix/docs"),
                           ("pull_request", "refs/pull/77/merge")]:
            for entries in [[("M", "README.md")], [("A", "docs/new.MD"), ("D", "old.md")],
                            [("D", "old.md"), ("A", "renamed.md")]]:
                with self.subTest(event=event, entries=entries):
                    self.assertFalse(scope.requires_android_checks(event, ref, entries))

    def test_mixed_code_workflow_and_non_markdown_renames_run(self):
        for entries in [[], [("M", "README.md"), ("M", "app/Main.kt")],
                        [("M", ".github/workflows/android.yml")],
                        [("D", "old.kt"), ("A", "new.md")],
                        [("D", "old.md"), ("A", "new.kt")],
                        [("R100", "README.md")], [("M", "guide.mdx")]]:
            self.assertTrue(scope.requires_android_checks("pull_request", "refs/pull/77/merge", entries))

    def test_dispatch_and_release_markers_always_run(self):
        for event in ("workflow_dispatch", "schedule"):
            self.assertTrue(scope.requires_android_checks(event, "refs/heads/main", [("M", "README.md")]))
        for msg in ("Release [publish-main-0.13.0]", "Release [publish-pr-48]"):
            self.assertTrue(scope.requires_android_checks("push", "refs/heads/main", [("M", "README.md")], msg))

    def test_generated_json_exception_remains_main_push_only(self):
        entries = [("M", scope.CHANNEL_FILE)]
        self.assertFalse(scope.requires_android_checks("push", "refs/heads/main", entries))
        for event, ref in [("pull_request", "refs/pull/77/merge"), ("push", "refs/heads/fix/docs")]:
            self.assertTrue(scope.requires_android_checks(event, ref, entries))
        for status in ("A", "D"):
            self.assertTrue(scope.requires_android_checks("push", "refs/heads/main", [(status, scope.CHANNEL_FILE)]))

    def test_acceptance_only_skips_expensive_android_jobs_without_hiding_code_changes(self):
        entries = [("M", scope.ACCEPTANCE_FILE)]
        for event, ref in [("push", "refs/heads/main"),
                           ("push", "refs/heads/docs/accept-1565-playback"),
                           ("pull_request", "refs/pull/79/merge")]:
            self.assertFalse(scope.requires_android_checks(event, ref, entries))
        for entries in [
            [("A", scope.ACCEPTANCE_FILE)], [("D", scope.ACCEPTANCE_FILE)],
            [("M", scope.ACCEPTANCE_FILE), ("M", "app/Main.kt")],
            [("M", scope.ACCEPTANCE_FILE), ("M", ".github/workflows/android.yml")],
            [("M", scope.ACCEPTANCE_FILE), ("M", scope.CHANNEL_FILE)],
        ]:
            self.assertTrue(scope.requires_android_checks("pull_request", "refs/pull/79/merge", entries))
        self.assertTrue(scope.requires_android_checks(
            "push", "refs/heads/main", [("M", scope.ACCEPTANCE_FILE)],
            "Release [publish-main-0.13.0]"))
        self.assertTrue(scope.requires_android_checks(
            "workflow_dispatch", "refs/heads/main", [("M", scope.ACCEPTANCE_FILE)]))

    def test_invalid_base_fails_open(self):
        for before in ("", "0" * 40, "bad"):
            with self.assertRaises(ValueError):
                scope.changed_entries(before, "b" * 40)

    def test_real_git_pr_diff_includes_all_commits_and_rename_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            def git(*args):
                return subprocess.check_output(["git", "-C", tmp, *args], text=True).strip()
            git("init", "-q")
            git("config", "user.email", "test@example.com")
            git("config", "user.name", "Test")
            (Path(tmp) / "Main.kt").write_text("code")
            git("add", "."); git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            (Path(tmp) / "README.md").write_text("docs")
            git("add", "."); git("commit", "-qm", "docs")
            with patch("os.getcwd", return_value=tmp):
                # Run the actual classifier from the repository so git uses its real history.
                script = str(Path(scope.__file__).resolve())
                event, output = Path(tmp) / "event.json", Path(tmp) / "result"
                def classify():
                    event.write_text(json.dumps({"pull_request": {"base": {"sha": base},
                        "head": {"sha": git("rev-parse", "HEAD")}}}))
                    output.unlink(missing_ok=True)
                    env = dict(os.environ, GITHUB_EVENT_NAME="pull_request",
                               GITHUB_REF="refs/pull/77/merge", GITHUB_EVENT_PATH=str(event),
                               GITHUB_OUTPUT=str(output))
                    subprocess.run(["python3", script], cwd=tmp, env=env, check=True, capture_output=True)
                    return output.read_text()
                self.assertIn("scope=markdown_only", classify())
                git("mv", "Main.kt", "Main.md"); git("commit", "-qm", "rename code")
                self.assertIn("run_android=true", classify())

    def test_push_output_and_missing_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            event, output = Path(tmp) / "event.json", Path(tmp) / "output"
            event.write_text(json.dumps({"before": "a" * 40, "head_commit": {"message": "docs"}}))
            env = dict(GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main",
                       GITHUB_EVENT_PATH=str(event), GITHUB_OUTPUT=str(output), GITHUB_SHA="b" * 40)
            with patch.dict(os.environ, env, clear=True):
                with patch.object(subprocess, "check_output", return_value=b"M\0README.md\0"):
                    scope.main()
                self.assertEqual(output.read_text(), "run_android=false\nscope=markdown_only\n")
                output.unlink()
                with patch.object(subprocess, "check_output", side_effect=subprocess.CalledProcessError(128, ["git"])):
                    scope.main()
                self.assertEqual(output.read_text(), "run_android=true\nscope=full_checks\n")


if __name__ == "__main__":
    unittest.main()
