#!/usr/bin/env bash
# Both calls independently reject physical devices before modifying anything.
set -euo pipefail
cp upgrade-input/previous.apk upgrade-input/stable.apk
bash tools/upgrade-check.sh
mv upgrade-results upgrade-stable-results
cp upgrade-input/oldest.apk upgrade-input/previous.apk
bash tools/upgrade-check.sh
mv upgrade-results upgrade-oldest-results
cp upgrade-input/stable.apk upgrade-input/previous.apk
mkdir -p upgrade-results
mv upgrade-stable-results upgrade-results/stable
mv upgrade-oldest-results upgrade-results/oldest
python3 - <<'PY'
from pathlib import Path
results=[Path('upgrade-results')/name/'result.txt' for name in ('stable','oldest')]
assert all(p.read_text().startswith('PASS:') for p in results)
Path('release-output/upgrade-check.txt').write_text('PASS: current stable and oldest supported baseline both upgraded.\n'+
    ''.join(name+': '+path.read_text() for name,path in zip(('stable','oldest'),results)))
PY
