"""Fail on CodeQL findings or incomplete SARIF, independently of upload status."""
import json
from pathlib import Path
import sys


def check(paths):
    if not paths:raise ValueError('No CodeQL SARIF output')
    findings=[]
    for path in paths:
        document=json.loads(path.read_text())
        if document.get('version')!='2.1.0' or not document.get('runs'):raise ValueError('Incomplete SARIF')
        for run in document['runs']:
            driver=run.get('tool',{}).get('driver',{})
            if driver.get('name')!='CodeQL' or not driver.get('rules'):raise ValueError('No CodeQL rules executed')
            for invocation in run.get('invocations',[]):
                if invocation.get('executionSuccessful') is False:raise ValueError('CodeQL execution failed')
                if any(n.get('level')=='error' for n in invocation.get('toolExecutionNotifications',[])):
                    raise ValueError('CodeQL reported analysis errors')
            if not isinstance(run.get('results'),list):raise ValueError('Missing CodeQL results')
            findings.extend(run['results'])
    if findings:raise ValueError(f'{len(findings)} CodeQL findings require review; no automatic suppression')


if __name__=='__main__':
    check(sorted(Path(sys.argv[1]).rglob('*.sarif')))
