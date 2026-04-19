#!/data/data/kle.ljubitje.apai/files/usr/bin/bash
# PAI Android — Setup Script (v2)
# Clones PAI repo and runs the official installer via Node.js
# (Bun's official binary doesn't run on Android/Bionic)
# Run: pai-setup

set -euo pipefail

BLUE='\033[1;34m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
RESET='\033[0m'

info()    { echo -e "  ${BLUE}[PAI]${RESET} $1"; }
success() { echo -e "  ${GREEN}[PAI]${RESET} $1"; }
warn()    { echo -e "  ${YELLOW}[PAI]${RESET} $1"; }
error()   { echo -e "  ${RED}[PAI]${RESET} $1"; }

echo ""
echo -e "${BLUE}╔══════════════════════════════════════╗${RESET}"
echo -e "${BLUE}║  PAI — Personal AI Infrastructure    ║${RESET}"
echo -e "${BLUE}║  Android Setup                       ║${RESET}"
echo -e "${BLUE}╚══════════════════════════════════════╝${RESET}"
echo ""

# ── Check prerequisites ──
missing=0

if ! command -v git &>/dev/null; then
    error "git not found. Run: pkg install git"
    missing=1
fi

if ! command -v curl &>/dev/null; then
    error "curl not found. Run: pkg install curl"
    missing=1
fi

if [ "$missing" -eq 1 ]; then
    error "Install missing prerequisites first."
    exit 1
fi

success "Prerequisites OK (curl, git)"

# ── Ensure environment is correct ──
export TMPDIR="${PREFIX}/tmp"
export CURL_CA_BUNDLE="${PREFIX}/etc/tls/cert.pem"
export SSL_CERT_FILE="${PREFIX}/etc/tls/cert.pem"
export GIT_EXEC_PATH="${PREFIX}/libexec/git-core"
export GIT_TEMPLATE_DIR="${PREFIX}/share/git-core/templates"
export GIT_SSL_CAINFO="${PREFIX}/etc/tls/cert.pem"
mkdir -p "$TMPDIR"

# ── Install Node.js if needed (Bun binary doesn't run on Android/Bionic) ──
if ! command -v node &>/dev/null; then
    info "Installing Node.js..."
    apt install -y nodejs-lts 2>&1
fi

if ! command -v node &>/dev/null; then
    error "Node.js installation failed."
    exit 1
fi
success "Node.js: $(node --version)"

# ── Install tsx (TypeScript executor) if needed ──
if ! command -v tsx &>/dev/null; then
    info "Installing tsx (TypeScript runner)..."
    npm install -g tsx 2>&1
    termux-fix-shebang "$PREFIX/bin/tsx" 2>/dev/null
fi
success "tsx ready"

# ── Install Claude Code if needed ──
CLAUDE_PKG_DIR="$PREFIX/lib/node_modules/@anthropic-ai/claude-code"
if ! command -v claude &>/dev/null || [ ! -f "$CLAUDE_PKG_DIR/bin/claude.exe" ]; then
    info "Installing Claude Code..."
    npm install -g @anthropic-ai/claude-code 2>&1
    termux-fix-shebang "$PREFIX/bin/claude" 2>/dev/null
fi

# ── Fix Claude Code native binary for Android ──
# Android's Node.js reports process.platform==="android" (not "linux"), and uses
# Bionic libc (not glibc or musl). Claude Code's native binary is built for
# linux-arm64-musl. To make it work on Android:
#  1. Force-install the musl native package (npm skips it due to os mismatch)
#  2. Install Alpine's musl dynamic linker (the binary needs ld-musl-aarch64.so.1)
#  3. Patch the binary's ELF interpreter from /lib/ld-musl-aarch64.so.1 to
#     the actual linker path under $PREFIX/lib/
#  4. Patch install.cjs/cli-wrapper.cjs so future invocations detect android→linux
MUSL_LD="$PREFIX/lib/ld-musl-aarch64.so.1"
CLAUDE_BIN="$CLAUDE_PKG_DIR/bin/claude.exe"

if [ -f "$CLAUDE_BIN" ] && head -1 "$CLAUDE_BIN" 2>/dev/null | grep -q "^echo"; then
    info "Fixing Claude Code native binary for Android..."
    CLAUDE_VER=$(node -e "console.log(require('$CLAUDE_PKG_DIR/package.json').version)")

    # Step 1: Force-install the musl native package (npm refuses due to os:linux vs android)
    info "Installing native binary (linux-arm64-musl)..."
    npm install -g --force "@anthropic-ai/claude-code-linux-arm64-musl@$CLAUDE_VER" 2>&1

    NATIVE_BIN="$PREFIX/lib/node_modules/@anthropic-ai/claude-code-linux-arm64-musl/claude"
    if [ ! -f "$NATIVE_BIN" ]; then
        error "Native binary package installation failed."
        exit 1
    fi

    # Step 2: Install musl dynamic linker from Alpine Linux
    if [ ! -f "$MUSL_LD" ]; then
        info "Installing musl dynamic linker..."
        MUSL_CACHE="${PREFIX}/var/cache/pai/musl-aarch64.apk"
        if [ ! -f "$MUSL_CACHE" ]; then
            # Discover the latest musl version from Alpine
            MUSL_VER=$(curl -fsSL "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/aarch64/" 2>/dev/null \
                | grep -o 'musl-[0-9][^"]*\.apk' | head -1)
            if [ -z "$MUSL_VER" ]; then
                error "Could not determine Alpine musl version."
                exit 1
            fi
            mkdir -p "$(dirname "$MUSL_CACHE")"
            curl -fsSL "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/aarch64/$MUSL_VER" -o "$MUSL_CACHE"
        fi
        cd "$TMPDIR"
        tar -xzf "$MUSL_CACHE" lib/ld-musl-aarch64.so.1 2>/dev/null
        if [ -f "$TMPDIR/lib/ld-musl-aarch64.so.1" ]; then
            cp "$TMPDIR/lib/ld-musl-aarch64.so.1" "$MUSL_LD"
            chmod 755 "$MUSL_LD"
            rm -rf "$TMPDIR/lib"
        else
            error "Failed to extract musl linker from Alpine package."
            exit 1
        fi
    fi

    # Step 3: Patch the ELF interpreter to point to our musl linker
    # The binary expects /lib/ld-musl-aarch64.so.1 which doesn't exist on Android.
    # Use node to binary-patch the interpreter path in the ELF header.
    info "Patching ELF interpreter..."
    cp "$NATIVE_BIN" "$CLAUDE_BIN"
    chmod 755 "$CLAUDE_BIN"
    node -e "
        const fs = require('fs');
        const buf = fs.readFileSync('$CLAUDE_BIN');
        const oldInterp = '/lib/ld-musl-aarch64.so.1';
        const newInterp = '$MUSL_LD';
        // Find PT_INTERP string in the binary
        const oldBuf = Buffer.from(oldInterp + '\0');
        const idx = buf.indexOf(oldBuf);
        if (idx === -1) { console.log('Interpreter already patched or not found'); process.exit(0); }
        // patchelf-style: write new interpreter (may be longer, overflows into padding)
        // ELF allows this if there's enough space before the next segment
        const newBuf = Buffer.from(newInterp + '\0');
        // Zero out old region first
        buf.fill(0, idx, idx + Math.max(oldBuf.length, newBuf.length));
        newBuf.copy(buf, idx);
        // Update PT_INTERP p_filesz and p_memsz in the program header
        // For 64-bit ELF: e_phoff at offset 32 (8 bytes), e_phentsize at 54 (2 bytes), e_phnum at 56 (2 bytes)
        const e_phoff = Number(buf.readBigUInt64LE(32));
        const e_phentsize = buf.readUInt16LE(54);
        const e_phnum = buf.readUInt16LE(56);
        for (let i = 0; i < e_phnum; i++) {
            const off = e_phoff + i * e_phentsize;
            const p_type = buf.readUInt32LE(off);
            if (p_type === 3) { // PT_INTERP
                // p_filesz at off+32, p_memsz at off+40 (64-bit ELF)
                buf.writeBigUInt64LE(BigInt(newBuf.length), off + 32);
                buf.writeBigUInt64LE(BigInt(newBuf.length), off + 40);
                break;
            }
        }
        fs.writeFileSync('$CLAUDE_BIN', buf);
        console.log('Interpreter patched: ' + oldInterp + ' → ' + newInterp);
    " 2>&1

    # Step 4: Patch install.cjs and cli-wrapper.cjs for android platform detection
    for f in "$CLAUDE_PKG_DIR/install.cjs" "$CLAUDE_PKG_DIR/cli-wrapper.cjs"; do
        if [ -f "$f" ] && ! grep -q "android" "$f"; then
            sed -i 's/function getPlatformKey() {/function getPlatformKey() {\n  if (process.platform === "android" \&\& require("os").arch() === "arm64") return "linux-arm64-musl";/' "$f"
        fi
    done

    # Step 5: Patch musl's hardcoded /etc/resolv.conf path for Android DNS
    # musl libc reads /etc/resolv.conf which doesn't exist on Android. Patch the
    # musl dynamic linker to use our $PREFIX/etc/resolv.conf instead.
    if [ -f "$MUSL_LD" ]; then
        info "Patching musl DNS resolver path..."
        node -e "
            const fs = require('fs');
            const buf = fs.readFileSync('$MUSL_LD');
            const oldPath = '/etc/resolv.conf';
            const newPath = '$PREFIX/etc/resolv.conf';
            const oldBuf = Buffer.from(oldPath + '\0');
            const idx = buf.indexOf(oldBuf);
            if (idx === -1) { console.log('resolv.conf path already patched or not found'); process.exit(0); }
            const newBuf = Buffer.from(newPath + '\0');
            buf.fill(0, idx, idx + Math.max(oldBuf.length, newBuf.length));
            newBuf.copy(buf, idx);
            fs.writeFileSync('$MUSL_LD', buf);
            console.log('Patched: ' + oldPath + ' → ' + newPath);
        " 2>&1
    fi

    success "Claude Code native binary patched for Android"
fi
success "Claude Code: $(claude --version 2>&1)"

# ── Obtain PAI release ──
# Prefer the APK-bundled tarball (fast, offline). Fall back to git clone when the
# bundle is missing or when the user explicitly requests a refresh via PAI_REFRESH=1.
PAI_REPO="$TMPDIR/pai-repo"
PAI_CLAUDE_DIR="$PAI_REPO/Releases/v4.0.3/.claude"
PAI_BUNDLE="${PREFIX}/var/cache/pai/pai-release.tar"
PAI_VERSION_FILE="${PREFIX}/var/cache/pai/pai-release.version"

use_bundle=0
if [ -f "$PAI_BUNDLE" ] && [ "${PAI_REFRESH:-0}" != "1" ]; then
    use_bundle=1
fi

if [ "$use_bundle" = "1" ]; then
    if [ -f "$PAI_VERSION_FILE" ]; then
        info "Extracting bundled PAI release ($(grep '^rev=' "$PAI_VERSION_FILE" | cut -c5-12))..."
    else
        info "Extracting bundled PAI release..."
    fi
    rm -rf "$PAI_REPO"
    mkdir -p "$PAI_REPO/Releases/v4.0.3"
    tar -xf "$PAI_BUNDLE" -C "$PAI_REPO/Releases/v4.0.3"
elif [ -d "$PAI_CLAUDE_DIR/PAI-Install" ]; then
    info "PAI repo already present, updating..."
    git -C "$PAI_REPO" pull --ff-only 2>/dev/null || true
else
    info "Cloning PAI repository (sparse)..."
    rm -rf "$PAI_REPO"
    git clone --depth 1 --sparse \
        "https://github.com/danielmiessler/Personal_AI_Infrastructure.git" \
        "$PAI_REPO"
    git -C "$PAI_REPO" sparse-checkout set "Releases/v4.0.3/.claude"
fi

if [ ! -f "$PAI_CLAUDE_DIR/PAI-Install/main.ts" ]; then
    error "PAI-Install/main.ts not found. Check repository structure."
    exit 1
fi

success "PAI repository ready."

# ── Deploy to ~/.claude (excluding settings.json for fresh-install detection) ──
info "Deploying PAI release to ~/.claude..."
rm -rf "$HOME/.claude"
cp -r "$PAI_CLAUDE_DIR" "$HOME/.claude"
rm -f "$HOME/.claude/settings.json"
success "PAI deployed to ~/.claude"

# ── Patch installer for Android/bash compatibility ──
PATCH_DIR="$TMPDIR/pai-patches"
if [ -d "$PATCH_DIR" ] && [ -f "$TMPDIR/patch-installer.sh" ]; then
    sh "$TMPDIR/patch-installer.sh" "$HOME/.claude" "$PATCH_DIR"
fi

# ── Run the PAI installer in CLI mode ──
info "Launching PAI installer (CLI mode via Node.js)..."
echo ""
exec tsx "$HOME/.claude/PAI-Install/main.ts" --mode cli
