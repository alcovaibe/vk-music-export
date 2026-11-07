"""Record public run/job/artifact identities before bundling a future release."""
import json
import os
from pathlib import Path
import subprocess

repo=os.environ['GITHUB_REPOSITORY'];run=os.environ['GITHUB_RUN_ID'];sha=os.environ['GITHUB_SHA']
def api(path):return json.loads(subprocess.check_output(['gh','api',path],text=True))
metadata=api(f'repos/{repo}/actions/runs/{run}')
if metadata['head_sha']!=sha or metadata['repository']['full_name']!=repo:raise ValueError('CI source changed')
source_run=os.environ.get('CANDIDATE_RUN',run)
source=api(f'repos/{repo}/actions/runs/{source_run}')
info=json.loads(Path('release-output/build-info.json').read_text())
if source['head_sha']!=info['commit'] or str(info.get('buildRun',source_run))!=source_run:
    raise ValueError('Original build run has another subject')
jobs=api(f'repos/{repo}/actions/runs/{source_run}/jobs?per_page=100')
if jobs['total_count']>100:raise ValueError('Job pagination required before bundling')
for required in ('build','interface'):
    if not any(j['name']==required and j['conclusion']=='success' for j in jobs['jobs']):raise ValueError('Required job not successful: '+required)
artifacts=api(f'repos/{repo}/actions/runs/{source_run}/artifacts?per_page=100')
if artifacts['total_count']>100:raise ValueError('Artifact pagination required before bundling')
result={'schema':1,'repository':repo,'verification_run':run,'verification_commit':sha,'source_run':source_run,'source_commit':source['head_sha'],'url':metadata['html_url'],
    'jobs':[{'id':j['id'],'name':j['name'],'conclusion':j['conclusion']} for j in jobs['jobs']],
    'artifacts':[{'id':a['id'],'name':a['name'],'digest':a.get('digest'),'size':a['size_in_bytes']} for a in artifacts['artifacts']]}
path=Path('release-evidence-input/ci-origin.json');path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(result,indent=2)+'\n')
