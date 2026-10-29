#!/usr/bin/env bash
# Both calls independently reject physical devices before modifying anything.
set -euo pipefail
stash=$(mktemp -d)
active=stable
collect_evidence() {
  local result=$?
  trap - EXIT
  set +e
  if [[ -n "$active" && -d upgrade-results ]]; then
    mv upgrade-results "$stash/$active" || result=1
  fi
  mkdir -p upgrade-results || result=1
  for baseline in stable oldest; do
    if [[ -d "$stash/$baseline" ]]; then mv "$stash/$baseline" "upgrade-results/$baseline" || result=1; fi
  done
  if [[ -f upgrade-input/stable.apk ]]; then cp upgrade-input/stable.apk upgrade-input/previous.apk || result=1; fi
  rmdir "$stash" 2>/dev/null || true
  # One passing baseline must never leave a misleading aggregate PASS behind.
  if [[ "$result" != 0 ]]; then rm -f release-output/upgrade-check.txt; fi
  exit "$result"
}
trap collect_evidence EXIT
rm -f release-output/upgrade-check.txt
cp upgrade-input/previous.apk upgrade-input/stable.apk
bash tools/upgrade-check.sh
mv upgrade-results "$stash/stable"
active=oldest
cp upgrade-input/oldest.apk upgrade-input/previous.apk
bash tools/upgrade-check.sh
mv upgrade-results "$stash/oldest"
active=''
cp upgrade-input/stable.apk upgrade-input/previous.apk
mkdir -p upgrade-results
mv "$stash/stable" upgrade-results/stable
mv "$stash/oldest" upgrade-results/oldest
python3 - <<'PY'
from pathlib import Path
results=[Path('upgrade-results')/name/'result.txt' for name in ('stable','oldest')]
assert all(p.read_text().startswith('PASS:') for p in results)
Path('release-output/upgrade-check.txt').write_text('PASS: current stable and oldest supported baseline both upgraded.\n'+
    ''.join(name+': '+path.read_text() for name,path in zip(('stable','oldest'),results)))
PY
