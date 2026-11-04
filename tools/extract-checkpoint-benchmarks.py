"""Require complete, bounded Android benchmark evidence, independently of Gradle exit status."""
import json
import math
from pathlib import Path
import sys


def nonnegative(row, fields, integers=False):
    for field in fields:
        value = row.get(field)
        allowed = (int,) if integers else (int, float)
        if type(value) not in allowed or not math.isfinite(value) or value < 0:
            raise ValueError(f'Invalid measured field: {field}')


def extract(text, api):
    records = {}
    for kind, prefix in [('metadata', 'VK_METADATA_BENCHMARK='), ('receipts', 'VK_RECEIPT_BENCHMARK=')]:
        found = [json.loads(line[len(prefix):]) for line in text.splitlines() if line.startswith(prefix)]
        if len(found) != 1:
            raise ValueError(f'Expected one complete {kind} benchmark, got {len(found)}')
        record = found[0]
        if record.get('schema') != 1 or record.get('api') != api:
            raise ValueError(f'{kind} schema/API mismatch')
        rows = record.get('results', [])
        size_field = 'targets' if kind == 'metadata' else 'receipts'
        if [row.get(size_field) for row in rows] != [100, 4500, 20000]:
            raise ValueError(f'{kind} workloads mismatch')
        for row in rows:
            size = row[size_field]
            if kind == 'metadata':
                nonnegative(row, ['snapshot_ms', 'checkpoint_ms', 'checkpoint_p95_ms', 'restore_ms'])
                nonnegative(row, ['target_writes', 'progress_writes', 'target_payload_bytes', 'progress_payload_bytes'], True)
                if row['target_writes'] != size or row['progress_writes'] != size + 1:
                    raise ValueError('Metadata write counts mismatch')
            else:
                nonnegative(row, ['begin_ms', 'save_ms', 'save_p95_ms', 'reopen_ms', 'read_all_ms'])
                nonnegative(row, ['receipt_inserts', 'receipt_updates', 'state_writes', 'receipt_text_bytes', 'state_text_bytes', 'observed_wal_size_bytes'], True)
                if row['receipt_inserts'] != size or row['receipt_updates'] != 0 or row['state_writes'] != size + 1:
                    raise ValueError('Receipt write counts mismatch')
        records[kind] = record
    return records


def main():
    source, api, destination = sys.argv[1:]
    records = extract(Path(source).read_text(), int(api))
    root = Path(destination)
    root.mkdir(parents=True, exist_ok=True)
    for kind, record in records.items():
        filename = f'api-{api}.json' if kind == 'metadata' else f'receipts-api-{api}.json'
        (root / filename).write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
    print(f'Checkpoint evidence verified: API {api}, metadata and receipts, three workloads each')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError) as error:
        raise SystemExit(f'Benchmark evidence rejected: {error}')
