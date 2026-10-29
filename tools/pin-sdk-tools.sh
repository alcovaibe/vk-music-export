#!/usr/bin/env bash
# android-emulator-runner prepends cmdline-tools/latest, so PATH alone cannot pin tools.
set -euo pipefail
sdk_root=${1:?Android SDK root is required}
canonical_tools="$sdk_root/cmdline-tools/latest"
original_tools="$sdk_root/cmdline-tools/vkm-runner-original"
pinned_tools=''
# setup-android v4 maps build 15859902 to 22.0. It can also reuse an
# already-correct latest directory instead of installing a versioned directory.
for candidate in "$sdk_root/cmdline-tools/22.0" "$sdk_root/cmdline-tools/15859902" "$canonical_tools"; do
  if [[ -x "$candidate/bin/avdmanager" && -x "$candidate/bin/sdkmanager" && -f "$candidate/source.properties" ]] &&
      grep -Eq '^Pkg.Revision=22[.]0([.]0)?[[:space:]]*$' "$candidate/source.properties"; then
    pinned_tools=$(realpath "$candidate")
    break
  fi
done
if [[ -z "$pinned_tools" ]]; then
  echo 'Android command-line tools 22.0 (build 15859902) with sdkmanager and avdmanager are required.' >&2
  exit 1
fi
if [[ "$(readlink -f "$canonical_tools")" != "$pinned_tools" ]]; then
  if [[ -e "$canonical_tools" || -L "$canonical_tools" ]]; then
    if [[ -e "$original_tools" || -L "$original_tools" ]]; then
      echo "Cannot preserve existing latest tools: backup already exists at $original_tools" >&2
      exit 1
    fi
    mv "$canonical_tools" "$original_tools"
  fi
  ln -s "$pinned_tools" "$canonical_tools"
fi
echo "Canonical emulator tools: $(readlink -f "$canonical_tools")"
"$canonical_tools/bin/sdkmanager" --version
