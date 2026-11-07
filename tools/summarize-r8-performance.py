"""Summarize observed AVD startup/frame samples; no device performance promise."""
import csv
import json
import math
from pathlib import Path
import re
import sys


def percentiles(values):
    if not values:raise ValueError('No performance samples')
    values=sorted(values)
    def at(q):
        index=(len(values)-1)*q;lower=int(index);upper=min(lower+1,len(values)-1)
        return values[lower]+(values[upper]-values[lower])*(index-lower)
    return {'samples':len(values),'p50':at(.5),'p95':at(.95),'p99':at(.99),'max':values[-1]}


def summarize(root, api):
    startup={}
    for mode in ('cold','warm'):
        values=[]
        for index in range(1,6):
            text=(root/f'{mode}-{index}.txt').read_text()
            value=re.search(r'^TotalTime:\s*(\d+)\s*$',text,re.M)
            if 'Error:' in text or not value:raise ValueError('Incomplete startup sample')
            values.append(int(value[1]))
        startup[mode+'_total_ms']=percentiles(values)
    frames=[];header=None;discarded=0
    for row in csv.reader((root/'frames.txt').read_text().splitlines()):
        if row and row[0]=='Flags':header={key:i for i,key in enumerate(row)};continue
        if not header or not row or not row[0].isdigit():continue
        try:
            duration=(int(row[header['FrameCompleted']])-int(row[header['IntendedVsync']]))/1_000_000
            if int(row[header['Flags']])!=0 or not math.isfinite(duration) or not 0<duration<60_000:
                discarded+=1;continue
            frames.append(duration)
        except (ValueError,KeyError,IndexError):raise ValueError('Malformed frame sample')
    return {'schema':1,'api':api,'subject':'AVD launcher/home with synthetic offline fixture; not a 20k library scroll benchmark',
            'startup':startup,'frame_ms':percentiles(frames),'discarded_frames':discarded}


if __name__=='__main__':
    api=int(sys.argv[1]);root=Path('r8-evidence');result=summarize(root,api)
    (root/f'performance-api-{api}.json').write_text(json.dumps(result,indent=2)+'\n')
