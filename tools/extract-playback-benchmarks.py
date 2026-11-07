"""Require measured history costs and bounded cursor writes for each Android API."""
import json
import math
from pathlib import Path
import sys


def extract(text, api):
    prefix='VK_PLAYBACK_BENCHMARK='
    rows=[json.loads(line[len(prefix):]) for line in text.splitlines() if line.startswith(prefix)]
    if len(rows)!=1:raise ValueError('Expected one complete playback benchmark')
    record=rows[0]
    if record.get('schema')!=1 or record.get('api')!=api:raise ValueError('Playback benchmark schema/API mismatch')
    results=record.get('results',[])
    if [r.get('items') for r in results]!=[100,4500,20000]:raise ValueError('Playback workloads mismatch')
    for row in results:
        for field in ['install_ms','restore_ms','updates_ms']:
            value=row.get(field)
            if type(value) not in (int,float) or not math.isfinite(value) or value<0:raise ValueError('Invalid measured playback cost')
        for field,expected in [('cursor_writes',100),('queue_rewrites',0)]:
            if type(row.get(field)) is not int or row[field]!=expected:raise ValueError('Unbounded playback cursor writes')
    return record


if __name__=='__main__':
    try:
        source,api,destination=sys.argv[1:]
        record=extract(Path(source).read_text(),int(api))
        root=Path(destination);root.mkdir(parents=True,exist_ok=True)
        (root/f'playback-api-{api}.json').write_text(json.dumps(record,indent=2)+'\n')
        print(f'Playback-history evidence verified: API {api}, 100/4500/20000 entries, constant cursor writes')
    except (ValueError,TypeError,KeyError,OSError) as error:raise SystemExit(f'Playback evidence rejected: {error}')
