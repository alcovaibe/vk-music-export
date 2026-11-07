"""Require a minified non-debuggable playback run, not just successful installation."""
import json
import hashlib
import subprocess
import math
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(root, api):
    mapping=root/'app/build/outputs/mapping/release/mapping.txt'
    if not mapping.is_file() or mapping.stat().st_size<100:
        raise ValueError('Missing actual R8 mapping')
    expected=('app.alcovaibe.vkmusicexport.R8PlaybackDeviceTest',
              'minifiedOfflinePlaybackPauseStopResumeAndBackgroundProduceMeasuredEvidence')
    executed=[]
    for path in (root/'app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            if any(case.find(k) is not None for k in ('failure','error','skipped')):
                details='; '.join(ET.tostring(case.find(k), encoding='unicode')[:8000]
                                  for k in ('failure','error','skipped') if case.find(k) is not None)
                raise ValueError(f'R8 instrumentation did not pass: {path}: '
                                 f'{case.get("classname")}.{case.get("name")}: {details}')
            executed.append((case.get('classname'),case.get('name')))
    if executed!=[expected]:raise ValueError('Expected exactly the dedicated R8 playback test')
    prefix='VK_R8_PLAYBACK='
    rows=[json.loads(line[len(prefix):]) for line in (root/'r8-evidence/playback.log').read_text().splitlines() if line.startswith(prefix)]
    if len(rows)!=1:raise ValueError('Missing or duplicate R8 measurements')
    row=rows[0]
    if row.get('schema')!=1 or row.get('api')!=api or row.get('debuggable') is not False:
        raise ValueError('Wrong R8 subject')
    for key in ('first_play_ms','cpu_ms','pss_kib','java_heap_bytes'):
        if type(row.get(key)) not in (float,int) or not math.isfinite(row[key]) or row[key]<0:
            raise ValueError('Invalid R8 measurement: '+key)
    if row.get('pause_seek_resume') is not True or row.get('stop_new_play') is not True or row.get('background_sample_ms')!=5000:
        raise ValueError('Incomplete playback checks')
    return row


if __name__=='__main__':
    api=int(sys.argv[1]);row=verify(Path('.'),api)
    row['source_commit']=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    row['apk_sha256']=hashlib.sha256(Path('app/build/outputs/apk/release/app-release.apk').read_bytes()).hexdigest()
    row['signing']='ephemeral runner debug key; release build type with R8'
    (Path('r8-evidence')/f'playback-api-{api}.json').write_text(json.dumps(row,indent=2)+'\n')
