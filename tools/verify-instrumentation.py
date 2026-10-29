"""Reject green Gradle runs that never execute the Android instrumentation suite."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(results: Path, minimum_tests: int = 83) -> int:
    for code in results.rglob('test-result-exit-code.txt'):
        if code.read_text().strip() != '0':
            raise ValueError('Android test runner returned a non-zero exit code')
    reports = list(results.rglob('TEST-*.xml'))
    if not reports:
        raise ValueError('No JUnit report: instrumentation did not complete; check APK installation and emulator logs')
    executed = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in root.iter():
            if suite.tag in ('testsuites', 'testsuite'):
                for counter in ('failures', 'errors', 'skipped'):
                    if int(suite.get(counter, '0')):
                        raise ValueError(f'Instrumentation report contains {counter}')
        for case in root.iter('testcase'):
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Instrumentation testcase failed or was skipped')
            identity = (case.get('classname'), case.get('name'))
            if not all(identity):
                raise ValueError('Instrumentation testcase is missing its identity')
            executed.add(identity)
    if len(executed) < minimum_tests:
        raise ValueError(f'Only {len(executed)} instrumentation tests executed; expected at least {minimum_tests}')
    if not any(name == 'app.alcovaibe.vkmusicexport.InterfaceTest' for name, _ in executed):
        raise ValueError('Required interface test did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.AuditRegressionDeviceTest' for name, _ in executed) < 9:
        raise ValueError('Required audit regression tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.AccessRecoveryDeviceTest' for name, _ in executed) < 2:
        raise ValueError('Required access recovery tests did not execute')
    return len(executed)


if __name__ == '__main__':
    try:
        count = verify(Path(sys.argv[1]))
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit(f'Instrumentation verification failed: {error}')
    print(f'Instrumentation verification passed: {count} distinct tests executed, no failures or skips.')
