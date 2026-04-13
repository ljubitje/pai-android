#!/data/data/kle.ljubitje.pai/files/usr/bin/bash
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
if ! command -v claude &>/dev/null; then
    info "Installing Claude Code..."
    npm install -g @anthropic-ai/claude-code 2>&1
    termux-fix-shebang "$PREFIX/bin/claude" 2>/dev/null
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
