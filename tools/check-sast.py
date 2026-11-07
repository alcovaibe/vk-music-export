"""Fail on CodeQL findings or incomplete SARIF, independently of upload status."""
import json
import hashlib
from pathlib import Path
import sys


def check(paths, reviewed=None):
    if not paths:raise ValueError('No CodeQL SARIF output')
    findings=[]
    reviews=json.loads(reviewed.read_text())['reviews'] if reviewed else []
    for entry in reviews:
        source=Path(entry['path'])
        if not entry.get('rationale') or not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest()!=entry['source_sha256']:
            raise ValueError('SAST review must be renewed for changed source: '+entry['path'])
    for path in paths:
        document=json.loads(path.read_text())
        if document.get('version')!='2.1.0' or not document.get('runs'):raise ValueError('Incomplete SARIF')
        for run in document['runs']:
            driver=run.get('tool',{}).get('driver',{})
            if driver.get('name')!='CodeQL' or not (driver.get('rules') or any(e.get('rules') for e in run.get('tool',{}).get('extensions',[]))):raise ValueError('No CodeQL rules executed')
            for invocation in run.get('invocations',[]):
                if invocation.get('executionSuccessful') is False:raise ValueError('CodeQL execution failed')
                if any(n.get('level')=='error' for n in invocation.get('toolExecutionNotifications',[])):
                    raise ValueError('CodeQL reported analysis errors')
            if not isinstance(run.get('results'),list):raise ValueError('Missing CodeQL results')
            for finding in run['results']:
                locations=finding.get('locations',[])
                location=locations[0].get('physicalLocation',{}) if len(locations)==1 else {}
                path=location.get('artifactLocation',{}).get('uri')
                line=location.get('region',{}).get('startLine')
                matches=[entry for entry in reviews if entry['rule']==finding.get('ruleId') and entry['path']==path and entry['line']==line]
                if len(matches)!=1:findings.append(finding)
    if findings:raise ValueError(f'{len(findings)} CodeQL findings require review; no automatic suppression')


if __name__=='__main__':
    check(sorted(Path(sys.argv[1]).rglob('*.sarif')),Path(sys.argv[2]) if len(sys.argv)>2 else None)
