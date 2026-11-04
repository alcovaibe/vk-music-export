"""Retry configuration once for the observed Compose plugin resolution failure.

This does not run or retry compilation/tests. A persistent resolution failure
still fails CI. Dependency verification remains strict on both attempts.
"""
from pathlib import Path
import re
import subprocess
import time
import tomllib


ROOT = Path(__file__).resolve().parent.parent
PLUGIN = "org.jetbrains.kotlin.plugin.compose"
# Verified on the official plugin portal; unknown future versions fail normally.
PUBLISHED_VERSION = "2.4.21"
BLOCKERS = re.compile(
    r"dependency verification failed|checksum (?:verification |validation )?failed|"
    r"expected (?:a )?(?:sha\d+|checksum)|signature verification failed|"
    r"PKIX|SSLHandshakeException|certificate_unknown|"
    r"\b(?:401|403)\b|Unauthorized|Forbidden|"
    r"(?m:^> Task )|Compilation error|Script compilation error",
    re.IGNORECASE,
)


def retryable(output, version):
    if version != PUBLISHED_VERSION or BLOCKERS.search(output):
        return False
    marker = f"{PLUGIN}:{PLUGIN}.gradle.plugin:{version}"
    return (
        f"Plugin [id: '{PLUGIN}', version: '{version}', apply: false]" in output
        and "was not found in any of the following sources" in output
        and f"could not resolve plugin artifact '{marker}'" in output
    )


def preflight(version, run=subprocess.run, sleep=time.sleep):
    command = ["./gradlew", "--dependency-verification", "strict",
               "--console=plain", "help", "--stacktrace", "--info"]
    for attempt in range(2):
        current = command + (["--refresh-dependencies"] if attempt else [])
        print(f"Gradle configuration preflight {attempt + 1}/2", flush=True)
        result = run(current, cwd=ROOT, stdout=subprocess.PIPE,
                     stderr=subprocess.STDOUT, text=True)
        print(result.stdout, end="", flush=True)
        if result.returncode == 0:
            return 0
        if result.returncode < 0 or attempt or not retryable(result.stdout, version):
            return result.returncode if result.returncode > 0 else 1
        print("Known published Compose plugin could not be resolved before tasks. "
              "Retrying configuration once in 10s with refreshed dependency metadata.",
              flush=True)
        sleep(10)
    raise AssertionError("unreachable")


def main():
    with (ROOT / "gradle/libs.versions.toml").open("rb") as source:
        version = tomllib.load(source)["versions"]["kotlin"]
    return preflight(version)


if __name__ == "__main__":
    raise SystemExit(main())
