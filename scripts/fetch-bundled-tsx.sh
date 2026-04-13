#!/usr/bin/env bash
# Fetch tsx + runtime deps into a tar for APK bundling.
#
# tsx itself is pure JS (dist/), but it hard-depends on `esbuild` which
# ships a platform-specific native binary. On Termux/Android that's
# @esbuild/android-arm64.
#
# Bundling plan:
#   lib/node_modules/tsx/                         (tsx package)
#   lib/node_modules/get-tsconfig/                (tsx runtime dep)
#   lib/node_modules/@esbuild/android-arm64/      (native esbuild for aarch64 bionic)
#   lib/node_modules/esbuild/                     (esbuild JS wrapper that loads the native)
#   bin/tsx                                       (shim that execs node dist/cli.mjs)
#
# Extract point on device: $PREFIX (i.e. /data/data/<pkg>/files/usr)
#
# Run from repo root:
#   scripts/fetch-bundled-tsx.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/app/src/main/assets"
OUT_TAR="$ASSETS_DIR/tsx-bundle.tar"
OUT_VERSION="$ASSETS_DIR/tsx-bundle.version"

TMPDIR_WORK="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_WORK"' EXIT

fetch_tarball() {
    local name="$1"
    local version_spec="${2:-latest}"
    local url
    url="$(npm view "$name@$version_spec" dist.tarball 2>/dev/null | tail -1)"
    if [ -z "$url" ]; then
        echo "  ERROR: could not resolve $name@$version_spec" >&2
        exit 1
    fi
    local file="$TMPDIR_WORK/$(basename "$url")"
    curl -sSL -o "$file" "$url"
    echo "$file"
}

extract_pkg() {
    # Extract an npm tarball (which has a package/ wrapper) into $1
    local tarball="$1" target="$2"
    mkdir -p "$target"
    tar -xzf "$tarball" -C "$target" --strip-components=1
}

# Resolve versions
TSX_VERSION="$(npm view tsx version)"
ESBUILD_VERSION="$(npm view 'tsx@'"$TSX_VERSION" dependencies.esbuild | tr -d '~^')"
GETTSCONFIG_VERSION="$(npm view 'tsx@'"$TSX_VERSION" dependencies.get-tsconfig | tr -d '~^')"
# get-tsconfig has its own runtime dep — resolve-pkg-maps. Missing it
# caused "Cannot find module 'resolve-pkg-maps'" at tsx load time.
RESOLVEPKGMAPS_VERSION="$(npm view 'get-tsconfig@'"$GETTSCONFIG_VERSION" dependencies.resolve-pkg-maps | tr -d '~^')"

echo "  tsx: $TSX_VERSION" >&2
echo "  esbuild: $ESBUILD_VERSION" >&2
echo "  get-tsconfig: $GETTSCONFIG_VERSION" >&2
echo "  resolve-pkg-maps: $RESOLVEPKGMAPS_VERSION" >&2

# Stage lib/node_modules tree
STAGE="$TMPDIR_WORK/stage"
NM="$STAGE/lib/node_modules"
BIN="$STAGE/bin"
mkdir -p "$NM/@esbuild" "$BIN"

TSX_TAR="$(fetch_tarball tsx "$TSX_VERSION")"
GTC_TAR="$(fetch_tarball get-tsconfig "$GETTSCONFIG_VERSION")"
RPM_TAR="$(fetch_tarball resolve-pkg-maps "$RESOLVEPKGMAPS_VERSION")"
ESB_TAR="$(fetch_tarball esbuild "$ESBUILD_VERSION")"
ESB_NATIVE_TAR="$(fetch_tarball '@esbuild/android-arm64' "$ESBUILD_VERSION")"

extract_pkg "$TSX_TAR" "$NM/tsx"
extract_pkg "$GTC_TAR" "$NM/get-tsconfig"
extract_pkg "$RPM_TAR" "$NM/resolve-pkg-maps"
extract_pkg "$ESB_TAR" "$NM/esbuild"
extract_pkg "$ESB_NATIVE_TAR" "$NM/@esbuild/android-arm64"

# esbuild's install.js normally downloads + places the native binary; we short-
# circuit by placing it ourselves where esbuild's wrapper expects to find it.
# The wrapper at runtime runs require("@esbuild/<platform-arch>") which Node
# resolves from node_modules. Mark it installed by nulling install.js.
if [ -f "$NM/esbuild/install.js" ]; then
    echo "// bundled by pai-android; native binary pre-installed" > "$NM/esbuild/install.js"
fi

# Wire up tsx's own node_modules so it can resolve its deps from the global tree.
# When Node runs $NM/tsx/dist/cli.mjs, it walks up looking for node_modules and
# finds $NM (our global prefix). That resolves get-tsconfig and esbuild correctly.

# bin/tsx shim — shebangs under Termux must point at the real node path on device.
cat > "$BIN/tsx" <<'EOF'
#!/data/data/kle.ljubitje.pai/files/usr/bin/node
require('/data/data/kle.ljubitje.pai/files/usr/lib/node_modules/tsx/dist/cli.cjs');
EOF
chmod 0755 "$BIN/tsx"

# Also produce .mjs-aware fallback
cat > "$BIN/tsx.mjs" <<'EOF'
#!/data/data/kle.ljubitje.pai/files/usr/bin/node
import('/data/data/kle.ljubitje.pai/files/usr/lib/node_modules/tsx/dist/cli.mjs');
EOF
chmod 0755 "$BIN/tsx.mjs"

# Write tar with paths relative to $PREFIX (i.e. top-level is bin/, lib/).
mkdir -p "$ASSETS_DIR"
tar -cf "$OUT_TAR" -C "$STAGE" bin lib

SIZE_KB="$(du -k "$OUT_TAR" | awk '{print $1}')"
FILES_COUNT="$(tar -tf "$OUT_TAR" | wc -l)"

{
  echo "tsx=$TSX_VERSION"
  echo "esbuild=$ESBUILD_VERSION"
  echo "get-tsconfig=$GETTSCONFIG_VERSION"
  echo "resolve-pkg-maps=$RESOLVEPKGMAPS_VERSION"
  echo "native=@esbuild/android-arm64"
  echo "files=$FILES_COUNT"
} > "$OUT_VERSION"

echo "  Wrote $OUT_TAR ($SIZE_KB KB, $FILES_COUNT entries)" >&2
echo "  Version pin: $OUT_VERSION" >&2
