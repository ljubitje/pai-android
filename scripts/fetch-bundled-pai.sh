#!/usr/bin/env bash
# Fetch the PAI release into app/src/main/assets/pai-release.tar.gz for APK bundling.
#
# Why bundle: PAI releases don't change very often. Shipping the current release
# inside the APK means first install doesn't need to clone ~23 MB of files from
# GitHub. pai-setup.sh still tries `git pull` afterwards, so users get any newer
# content automatically.
#
# Usage:
#   scripts/fetch-bundled-pai.sh            # fetch latest from main
#   scripts/fetch-bundled-pai.sh <ref>      # fetch a specific ref (branch/tag/sha)
#
# Re-runnable: always produces a fresh tarball.

set -euo pipefail

REPO_URL="https://github.com/danielmiessler/Personal_AI_Infrastructure.git"
RELEASE_SUBDIR="Releases/v4.0.3/.claude"
REF="${1:-main}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/app/src/main/assets"
# NOTE: uncompressed .tar — aapt auto-strips .gz wrappers anyway, and the APK's
# own zip-level deflate compresses the .tar nearly as well as gzip would.
OUT_TAR="$ASSETS_DIR/pai-release.tar"
OUT_VERSION="$ASSETS_DIR/pai-release.version"

TMPDIR_WORK="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_WORK"' EXIT

echo "  Cloning $REPO_URL (sparse, ref=$REF)..." >&2
git clone --depth 1 --branch "$REF" --sparse "$REPO_URL" "$TMPDIR_WORK/pai" >&2 \
  || git clone --depth 1 --sparse "$REPO_URL" "$TMPDIR_WORK/pai" >&2
git -C "$TMPDIR_WORK/pai" sparse-checkout set "$RELEASE_SUBDIR" >&2

SRC="$TMPDIR_WORK/pai/$RELEASE_SUBDIR"
if [ ! -d "$SRC" ]; then
  echo "  ERROR: $RELEASE_SUBDIR not found in clone" >&2
  exit 1
fi

FILES="$(find "$SRC" -type f | wc -l)"
SIZE_KB="$(du -sk "$SRC" | awk '{print $1}')"
REV="$(git -C "$TMPDIR_WORK/pai" rev-parse HEAD)"
DATE="$(git -C "$TMPDIR_WORK/pai" log -1 --format=%cI)"

echo "  $FILES files, $SIZE_KB KB, rev $REV" >&2

mkdir -p "$ASSETS_DIR"
# Archive with the .claude directory at the root so extraction gives ./.claude/
tar -cf "$OUT_TAR" -C "$(dirname "$SRC")" "$(basename "$SRC")"

{
  echo "ref=$REF"
  echo "rev=$REV"
  echo "date=$DATE"
  echo "files=$FILES"
  echo "subdir=$RELEASE_SUBDIR"
} > "$OUT_VERSION"

TAR_KB="$(du -k "$OUT_TAR" | awk '{print $1}')"
echo "  Wrote $OUT_TAR ($TAR_KB KB)" >&2
echo "  Version pin: $OUT_VERSION" >&2
