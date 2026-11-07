"""Skip Android work only for proven Markdown-only changes or generated channel pushes.

Manual runs, release markers, mixed changes and unavailable history run full CI.
"""
import json
import os
import re
import subprocess
from pathlib import Path

CHANNEL_FILE = "docs/updates/android.json"
SHA = re.compile(r"[0-9a-f]{40}\Z")


def markdown_only(entries):
    return bool(entries) and all(
        status in ("A", "M", "D", "T") and path.lower().endswith(".md")
        for status, path in entries
    )


def requires_android_checks(event_name, ref, entries, message=""):
    if event_name not in ("push", "pull_request") or "[publish-" in message:
        return True
    if markdown_only(entries):
        return False
    return not (
        event_name == "push" and ref == "refs/heads/main"
        and entries == [("M", CHANNEL_FILE)]
    )


def changed_entries(before, head):
    if not SHA.fullmatch(before) or not SHA.fullmatch(head) or before == "0" * 40:
        raise ValueError("Missing historical base; run all Android checks")
    output = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-status", "-z", before, head, "--"],
    )
    fields = output.decode("utf-8").split("\0")
    if fields[-1] != "" or (len(fields) - 1) % 2:
        raise ValueError("Unrecognized git diff output; run all Android checks")
    return list(zip(fields[:-1:2], fields[1:-1:2]))


def main():
    env = os.environ
    full, reason = True, "full_checks"
    event = env.get("GITHUB_EVENT_NAME")
    if event in ("push", "pull_request"):
        try:
            payload = json.loads(Path(env["GITHUB_EVENT_PATH"]).read_text())
            if event == "pull_request":
                pr = payload["pull_request"]
                base, head = pr["base"]["sha"], pr["head"]["sha"]
                if not SHA.fullmatch(base) or not SHA.fullmatch(head):
                    raise ValueError("Invalid PR commit")
                base = subprocess.check_output(
                    ["git", "merge-base", base, head], text=True,
                ).strip()
                message = ""
            else:
                base, head = payload.get("before", ""), env.get("GITHUB_SHA", "")
                message = (payload.get("head_commit") or {}).get("message", "")
            diff = changed_entries(base, head)
            full = requires_android_checks(event, env.get("GITHUB_REF", ""), diff, message)
            if not full:
                reason = "markdown_only" if markdown_only(diff) else "generated_update_json_only"
        except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as exc:
            print(f"Cannot prove documentation-only change: {exc}; running full checks.")
    value = "true" if full else "false"
    print(f"run_android={value}; scope={reason}")
    with open(env["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        out.write(f"run_android={value}\n")
        out.write(f"scope={reason}\n")


if __name__ == "__main__":
    main()
