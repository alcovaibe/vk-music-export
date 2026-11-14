import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const REPOSITORY = 'alcovaibe/vk-music-export';
const API = 'https://api.github.com/repos/' + REPOSITORY + '/releases';

export async function countApkDownloads(fetcher = fetch, token = '') {
  const seen = new Set();
  let total = 0;

  for (let page = 1; page <= 100; page++) {
    const response = await fetcher(API + '?per_page=100&page=' + page, {
      headers: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'vk-music-export-download-counter',
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
      },
      redirect: 'error',
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) {
      throw new Error('GitHub releases HTTP ' + response.status);
    }

    const releases = await response.json();
    if (!Array.isArray(releases)) throw new Error('Invalid releases response');

    for (const release of releases) {
      if (typeof release?.draft !== 'boolean' || !Array.isArray(release.assets)) {
        throw new Error('Invalid release metadata');
      }
      if (release.draft) continue;

      // All published releases, including prereleases; APKs only.
      for (const asset of release.assets) {
        if (typeof asset?.name !== 'string') throw new Error('Invalid release asset');
        if (!/\.apk$/i.test(asset.name) || asset.state !== 'uploaded') continue;
        if (!Number.isSafeInteger(asset.id) || asset.id <= 0 ||
            !Number.isSafeInteger(asset.download_count) || asset.download_count < 0) {
          throw new Error('Invalid APK download count');
        }
        if (seen.has(asset.id)) continue;
        seen.add(asset.id);
        total += asset.download_count;
        if (!Number.isSafeInteger(total)) throw new Error('Download count overflow');
      }
    }

    if (releases.length < 100) return total;
  }
  throw new Error('Too many release pages; refusing an incomplete count');
}

export function badgeSvg(total) {
  if (!Number.isSafeInteger(total) || total < 0) throw new Error('Invalid count');

  const label = 'Количество скачиваний';
  const left = 158;
  const right = Math.max(44, String(total).length * 7 + 20);
  const width = left + right;

  return '<svg xmlns="http://www.w3.org/2000/svg" width="' + width +
    '" height="20" role="img" aria-label="' + label + ': ' + total +
    '"><title>' + label + ': ' + total +
    '</title><linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/>' +
    '<stop offset="1" stop-opacity=".1"/></linearGradient><clipPath id="r"><rect width="' +
    width + '" height="20" rx="3"/></clipPath><g clip-path="url(#r)"><path fill="#555" d="M0 0h' +
    left + 'v20H0z"/><path fill="#007ec6" d="M' + left + ' 0h' + right +
    'v20h-' + right + 'z"/><path fill="url(#s)" d="M0 0h' + width +
    'v20H0z"/></g><g fill="#fff" text-anchor="middle" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11">' +
    '<text x="' + left / 2 + '" y="15" fill="#010101" fill-opacity=".3">' + label +
    '</text><text x="' + left / 2 + '" y="14">' + label +
    '</text><text x="' + (left + right / 2) +
    '" y="15" fill="#010101" fill-opacity=".3">' + total +
    '</text><text x="' + (left + right / 2) + '" y="14">' + total +
    '</text></g></svg>\n';
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  const output = process.argv[2];
  if (!output) throw new Error('Usage: node scripts/download-counter.mjs <output.svg>');
  const count = await countApkDownloads(fetch, process.env.GITHUB_TOKEN || '');
  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, badgeSvg(count), 'utf8');
  console.log('Counted ' + count + ' public APK downloads (including prereleases).');
}
