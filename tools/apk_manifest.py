"""Read the SDK requirement from the build configuration and the actual release APK."""
from pathlib import Path
import re

PACKAGE = 'app.alcovaibe.vkmusicexport'
BUILD_FILE = Path(__file__).resolve().parents[1] / 'app/build.gradle.kts'


def configured_min_sdk(path=BUILD_FILE):
    values = re.findall(r'^\s*minSdk\s*=\s*(\d+)\s*$', path.read_text(), re.MULTILINE)
    if len(values) != 1 or not 1 <= int(values[0]) <= 10000:
        raise ValueError('Expected one literal minSdk in the Android build configuration')
    return int(values[0])


def release_manifest(text, expected_min_sdk=None):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.MULTILINE)
    sdk = re.findall(r"^sdkVersion:'(\d+)'$", text, re.MULTILINE)
    if not package or package[1] != PACKAGE or 'application-debuggable' in text:
        raise ValueError('Expected a non-debuggable VK Music Export APK')
    if len(sdk) != 1 or not 1 <= int(sdk[0]) <= 10000:
        raise ValueError('APK is missing a valid minimum SDK')
    minimum = int(sdk[0])
    if expected_min_sdk is not None and minimum != expected_min_sdk:
        raise ValueError(f'APK minimum SDK {minimum} differs from build configuration {expected_min_sdk}')
    code = int(package[2])
    if not 0 < code <= 2_100_000_000:
        raise ValueError('APK has an invalid versionCode')
    return {'applicationId': package[1], 'versionCode': code, 'versionName': package[3], 'minSdk': minimum}
