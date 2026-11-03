"""Classify a main push without weakening PR or Android-source checks.

Only a *single modification* of the generated update-channel JSON can avoid
rebuilding nine emulator images. Ambiguous Git history and release requests
fail open: they run the complete Android CI.
"""
import json
import os
import re
import subprocess
from pathlib import Path

CHANNEL_FILE = "docs/updates/android.json"
SHA = re.compile(r"[0-9a-f]{40}\Z")


def requires_android_checks(event_name, ref, entries, message=""):
    if event_name != "push" or ref != "refs/heads/main":
        return True
    if "[publish-main-" in message:
        return True
    return entries != [("M", CHANNEL_FILE)]


def changed_entries(before, head):
    if not SHA.fullmatch(before) or not SHA.fullmatch(head) or before == "0" * 40:
        raise ValueError("Missing historical base for the push; run all Android checks")
    output = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-status", before, head, "--"],
        text=True,
    )
    entries = []
    for line in output.splitlines():
        fields = line.split("\t", 1)
        if len(fields) != 2:
            raise ValueError("Unrecognized git diff output; run all Android checks")
        entries.append((fields[0], fields[1]))
    return entries


def main():
    env = os.environ
    full = True
    reason = "full_checks"
    if env.get("GITHUB_EVENT_NAME") == "push" and env.get("GITHUB_REF") == "refs/heads/main":
        try:
            payload = json.loads(Path(env["GITHUB_EVENT_PATH"]).read_text())
            diff = changed_entries(payload.get("before", ""), env.get("GITHUB_SHA", ""))
            full = requires_android_checks(
                "push", env["GITHUB_REF"], diff,
                (payload.get("head_commit") or {}).get("message", ""),
            )
            reason = "full_checks" if full else "generated_update_json_only"
        except (OSError, ValueError, subprocess.CalledProcessError) as exc:
            # Never accidentally suppress required tests when history is unavailable.
            print(f"Cannot prove docs-only change: {exc}; running full checks.")
    value = "true" if full else "false"
    print(f"run_android={value}; scope={reason}")
    with open(env["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        out.write(f"run_android={value}\n")
        out.write(f"scope={reason}\n")


if __name__ == "__main__":
    main()
