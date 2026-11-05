"""Reject absent, incomplete or unbounded queue-read benchmark evidence."""
import json
import math
from pathlib import Path
import sys


def extract(text, api):
    prefix='VK_QUEUE_BENCHMARK='
    found=[json.loads(line[len(prefix):]) for line in text.splitlines() if line.startswith(prefix)]
    if len(found)!=1:raise ValueError('Expected one complete queue benchmark')
    record=found[0]
    if record.get('schema')!=1 or record.get('api')!=api:raise ValueError('Queue benchmark schema/API mismatch')
    rows=record.get('results',[])
    if [row.get('tasks') for row in rows]!=[100,4500,20000]:raise ValueError('Queue benchmark workloads mismatch')
    for row in rows:
        for field in ['snapshot_ms','page_ms','report_cursor_ms','reopen_ms']:
            value=row.get(field)
            if type(value) not in (int,float) or not math.isfinite(value) or value<0:raise ValueError(f'Invalid measured field: {field}')
        for field,bound in [('snapshot_queries',4),('page_queries',7),('report_queries',1)]:
            value=row.get(field)
            if type(value) is not int or not 1<=value<=bound:raise ValueError(f'Unbounded queue read: {field}')
    return record


def main():
    source,api,destination=sys.argv[1:]
    record=extract(Path(source).read_text(),int(api))
    root=Path(destination);root.mkdir(parents=True,exist_ok=True)
    (root/f'queue-api-{api}.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    print(f'Queue-read evidence verified: API {api}, 100/4500/20000 failures, bounded SQL queries')


if __name__=='__main__':
    try:main()
    except (ValueError,TypeError,KeyError,OSError) as error:raise SystemExit(f'Queue-read evidence rejected: {error}')
