import importlib.util
import json
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

source = Path(__file__).with_name("ci-change-scope.py")
spec = importlib.util.spec_from_file_location("ci_change_scope", source)
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class AndroidScopeTest(unittest.TestCase):
    def test_only_single_modified_generated_json_skips_main_emulators(self):
        self.assertFalse(scope.requires_android_checks(
            "push", "refs/heads/main", [("M", scope.CHANNEL_FILE)]))
        cases = [
            [("M", scope.CHANNEL_FILE), ("M", "app/src/main/AndroidManifest.xml")],
            [("A", scope.CHANNEL_FILE)],
            [("D", scope.CHANNEL_FILE)],
            [("M", "README.md")],
            [],
        ]
        for entries in cases:
            with self.subTest(entries=entries):
                self.assertTrue(scope.requires_android_checks(
                    "push", "refs/heads/main", entries))

    def test_prs_dispatch_and_release_markers_always_run_all_nine_apis(self):
        entries = [("M", scope.CHANNEL_FILE)]
        for event, ref in [
            ("pull_request", "refs/pull/65/merge"),
            ("workflow_dispatch", "refs/heads/main"),
            ("push", "refs/heads/codex/music-followups"),
        ]:
            with self.subTest(event=event):
                self.assertTrue(scope.requires_android_checks(event, ref, entries))
        for msg in ["Release [publish-main-0.12.0]", "Publish [publish-main-0.12.1]"]:
            self.assertTrue(scope.requires_android_checks(
                "push", "refs/heads/main", entries, msg))

    def test_missing_push_base_fails_open(self):
        for before in ["", "0" * 40, "not-a-commit"]:
            with self.subTest(before=before), self.assertRaises(ValueError):
                scope.changed_entries(before, "a" * 40)

    def test_github_output_and_git_diff_are_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            event = Path(tmp) / "event.json"
            output = Path(tmp) / "output"
            event.write_text(json.dumps({
                "before": "a" * 40,
                "head_commit": {"message": "docs: publish stable update channel"},
            }))
            env = {
                "GITHUB_EVENT_NAME": "push",
                "GITHUB_REF": "refs/heads/main",
                "GITHUB_EVENT_PATH": str(event),
                "GITHUB_OUTPUT": str(output),
                "GITHUB_SHA": "b" * 40,
            }
            with patch.dict(os.environ, env, clear=True):
                with patch.object(subprocess, "check_output", return_value="M\tdocs/updates/android.json\n"):
                    scope.main()
                self.assertEqual(
                    output.read_text(),
                    "run_android=false\nscope=generated_update_json_only\n",
                )
                output.unlink()
                with patch.object(subprocess, "check_output",
                                  side_effect=subprocess.CalledProcessError(128, ["git", "diff"])):
                    scope.main()
                self.assertEqual(output.read_text(), "run_android=true\nscope=full_checks\n")


if __name__ == "__main__":
    unittest.main()
