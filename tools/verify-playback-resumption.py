"""Reject missing phases, reused processes, failed assertions or incomplete resumption proofs."""
import json
from pathlib import Path
import re
import sys


def verify(root, api):
    for phase in ['seed','resume','active','cleared']:
        text=(root/f'{phase}.txt').read_text()
        if len(re.findall(r'OK \(1 test\)',text))!=1 or any(s in text for s in ['FAILURES!!!','INSTRUMENTATION_FAILED','INSTRUMENTATION_ABORTED','Process crashed']):
            raise ValueError('Playback phase did not pass: '+phase)
    proof=json.loads((root/'proof.json').read_text())
    if proof.get('api')!=api or proof.get('paused_position')!=321:raise ValueError('Wrong playback API/position')
    pids=[proof.get(phase+'_pid') for phase in ['seed','resume','active','cleared']]
    if any(type(pid) is not int or pid<=0 for pid in pids) or len(set(pids))!=4:raise ValueError('Playback phases did not use four independent processes')
    if sorted(proof.get('order',[]))!=[0,1,2,3]:raise ValueError('Lost playback permutation')
    for field in ['media_session_resumption','paused_restore','active_restore','explicit_stop','cleared_after_restart']:
        if proof.get(field) is not True:raise ValueError('Missing playback evidence: '+field)
    return proof


if __name__=='__main__':
    try:
        verify(Path(sys.argv[1]),int(sys.argv[2]))
        print('Playback process-death verification passed: paused + active kill, MediaSession play, explicit stop, no autoplay.')
    except (ValueError,TypeError,KeyError,OSError) as error:raise SystemExit(f'Playback resumption rejected: {error}')
