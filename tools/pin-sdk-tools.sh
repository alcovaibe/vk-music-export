#!/usr/bin/env bash
# android-emulator-runner prepends cmdline-tools/latest, so PATH alone cannot pin tools.
set -euo pipefail
sdk_root=${1:?Android SDK root is required}
pinned_tools=$(realpath "$sdk_root/cmdline-tools/15859902")
canonical_tools="$sdk_root/cmdline-tools/latest"
original_tools="$sdk_root/cmdline-tools/vkm-runner-original"
test -x "$pinned_tools/bin/avdmanager"
test -x "$pinned_tools/bin/sdkmanager"
grep -Eq '^Pkg.Revision=22[.]0([.]0)?[[:space:]]*$' "$pinned_tools/source.properties"
if [[ "$(readlink -f "$canonical_tools")" != "$pinned_tools" ]]; then
  if [[ -e "$canonical_tools" || -L "$canonical_tools" ]]; then
    test ! -e "$original_tools"
    mv "$canonical_tools" "$original_tools"
  fi
  ln -s "$pinned_tools" "$canonical_tools"
fi
echo "Canonical emulator tools: $(readlink -f "$canonical_tools")"
"$canonical_tools/bin/sdkmanager" --version
