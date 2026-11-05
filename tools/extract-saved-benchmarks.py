"""Require measured saved-index workloads; never substitute absence of evidence with a green test count."""
import json
import math
from pathlib import Path
import sys


def extract(text, api):
    prefix = 'VK_SAVED_BENCHMARK='
    found = [json.loads(line[len(prefix):]) for line in text.splitlines() if line.startswith(prefix)]
    if len(found) != 1:
        raise ValueError(f'Expected one complete saved-index benchmark, got {len(found)}')
    record = found[0]
    if record.get('schema') != 1 or record.get('api') != api:
        raise ValueError('Saved-index schema/API mismatch')
    rows = record.get('results', [])
    if [row.get('files') for row in rows] != [100, 4500, 20000]:
        raise ValueError('Saved-index workloads mismatch')
    for row in rows:
        for field in ['write_ms', 'first20_ms', 'reopen_ms', 'queue_build_ms', 'paged_read_ms', 'alias_us', 'pages']:
            value = row.get(field)
            if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                raise ValueError(f'Invalid saved-index measured field: {field}')
        if row.get('pages') != (row['files'] + 99) // 100 or row.get('uri_index') is not True:
            raise ValueError('Saved-index paging/index evidence mismatch')
    return record


def main():
    source, api, destination = sys.argv[1:]
    record = extract(Path(source).read_text(), int(api))
    root = Path(destination)
    root.mkdir(parents=True, exist_ok=True)
    (root / f'saved-api-{api}.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
    print(f'Saved-index evidence verified: API {api}, 100/4500/20000 files')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError) as error:
        raise SystemExit(f'Saved-index benchmark evidence rejected: {error}')
