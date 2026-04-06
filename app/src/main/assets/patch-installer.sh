#!/bin/sh
# Patch PAI installer for Android/bash compatibility
# Replaces upstream files that hardcode .zshrc with shell-aware versions
# (Based on pai-fork commits 6158ffd and 13983e3)
set -e
BASE="$1"
PATCHES="$2"
[ -d "$BASE/PAI-Install" ] || [ -d "$BASE/PAI" ] || exit 0

# Overwrite with shell-aware versions (detect bash/zsh/fish from $SHELL)
for pair in \
  "validate.ts:PAI-Install/engine/validate.ts" \
  "types.ts:PAI-Install/engine/types.ts" \
  "display.ts:PAI-Install/cli/display.ts" \
  "index.ts:PAI-Install/cli/index.ts" \
  "pai.ts:PAI/Tools/pai.ts"; do
  src="${pair%%:*}"
  dst="${pair#*:}"
  if [ -f "$PATCHES/$src" ] && [ -f "$BASE/$dst" ]; then
    cp "$PATCHES/$src" "$BASE/$dst"
  fi
done
