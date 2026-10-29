"""CycloneDX inventory and fail-closed OSV scan of resolved Maven dependencies."""
import argparse
import json
import urllib.request
from pathlib import Path
from urllib.parse import quote


def components(inventory):
    result = []
    for line in sorted(set(inventory.splitlines())):
        if not line.strip():
            continue
        parts = line.split(':')
        if len(parts) != 3 or not all(parts):
            raise ValueError(f'Invalid resolved coordinate: {line}')
        group, name, version = parts
        result.append({'type': 'library', 'group': group, 'name': name, 'version': version,
                       'purl': f'pkg:maven/{quote(group, safe="")}/{quote(name, safe="")}@{quote(version, safe="")} ' .strip()})
    if not result:
        raise ValueError('Empty dependency inventory')
    return result


def query(payload):
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
        data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def scan(items, request=query):
    findings = []
    for offset in range(0, len(items), 100):
        batch = items[offset:offset+100]
        active = [(item, None) for item in batch]
        for _ in range(20):
            queries = []
            for item, token in active:
                q = {'package': {'name': item['group']+':'+item['name'], 'ecosystem': 'Maven'}, 'version': item['version']}
                if token:
                    q['page_token'] = token
                queries.append(q)
            response = request({'queries': queries})
            results = response.get('results')
            if not isinstance(results, list) or len(results) != len(active):
                raise ValueError('Invalid or incomplete OSV response')
            following = []
            for (item, _), result in zip(active, results):
                if not isinstance(result, dict) or result.get('error'):
                    raise ValueError('OSV query failed')
                vulns = result.get('vulns', [])
                if not isinstance(vulns, list):
                    raise ValueError('Invalid OSV vulnerability list')
                for vuln in vulns:
                    if not isinstance(vuln.get('id'), str) or not vuln['id']:
                        raise ValueError('OSV finding has no identity')
                    findings.append({'purl': item['purl'], 'id': vuln['id']})
                token = result.get('next_page_token')
                if token:
                    if not isinstance(token, str):
                        raise ValueError('Invalid OSV pagination token')
                    following.append((item, token))
            if not following:
                break
            active = following
        else:
            raise ValueError('OSV pagination limit exceeded; scan incomplete')
    return findings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inventory', default='app/build/reports/dependencies/resolved.txt')
    parser.add_argument('--sbom', help='Re-scan an existing candidate SBOM before promotion')
    parser.add_argument('--output', default='dependency-reports')
    args = parser.parse_args()
    out = Path(args.output); out.mkdir(parents=True, exist_ok=True)
    if args.sbom:
        candidate = json.loads(Path(args.sbom).read_text())
        if candidate.get('bomFormat') != 'CycloneDX' or not isinstance(candidate.get('components'), list):
            raise ValueError('Invalid candidate SBOM')
        items = components('\n'.join(c['group']+':'+c['name']+':'+c['version'] for c in candidate['components']))
    else:
        items = components(Path(args.inventory).read_text())
    (out/'sbom.cdx.json').write_text(json.dumps({'bomFormat':'CycloneDX', 'specVersion':'1.6', 'version':1,
        'components':items}, ensure_ascii=False, indent=2)+'\n')
    # Network errors propagate. A failed scanner must never report a clean result.
    findings = scan(items)
    (out/'osv.json').write_text(json.dumps({'scanner':'OSV querybatch', 'components':len(items),
        'findings':findings}, indent=2)+'\n')
    if findings:
        raise SystemExit(f'OSV found {len(findings)} affected dependency records; inspect dependency-reports/osv.json')
    print(f'PASS: {len(items)} resolved dependencies scanned; no OSV findings.')


if __name__ == '__main__':
    main()
