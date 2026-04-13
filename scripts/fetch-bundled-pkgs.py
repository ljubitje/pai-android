#!/usr/bin/env python3
"""
Fetch Termux aarch64 .deb files into app/src/main/assets/pkgs/ for APK bundling.

Strategy:
  1. Parse the Termux aarch64 Packages index.
  2. Parse the bootstrap's dpkg status file to know what's already preinstalled.
  3. Resolve the transitive Depends closure for the requested root packages.
  4. Skip anything already satisfied by the bootstrap (same-or-newer version).
  5. Download the resulting .debs with SHA256 verification.
  6. Write manifest.txt listing filename + installed-size for traceability.

Run from repo root:
    python3 scripts/fetch-bundled-pkgs.py

Re-runnable: if a file is already present and checksum matches, the download
is skipped. Use --clean to force-redownload.
"""

import argparse
import gzip
import hashlib
import io
import os
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

# ── Configuration ──
MIRROR = "https://packages-cf.termux.dev/apt/termux-main"
ARCH = "aarch64"
PACKAGES_URL = f"{MIRROR}/dists/stable/main/binary-{ARCH}/Packages.gz"

REPO_ROOT = Path(__file__).resolve().parent.parent
BOOTSTRAP_ZIP = REPO_ROOT / "app/src/main/assets/bootstrap-aarch64.zip"
OUT_DIR = REPO_ROOT / "app/src/main/assets/pkgs"
MANIFEST = OUT_DIR / "manifest.txt"

# Root packages we want available offline. First-install bottlenecks:
#   - nodejs-lts  (~20 MB, dominates first-run download)
#   - git         (used by pai-setup.sh to clone PAI)
#   - proot       (used by Claude Code / PAI at runtime)
ROOT_PACKAGES = ["nodejs-lts", "git", "proot"]

# Virtual packages / alternatives that aren't real packages in the index.
# Map them to concrete providers we want to pick.
VIRTUAL_PROVIDERS = {
    "awk": "gawk",
    "sh": None,  # satisfied by bash in bootstrap
    "dash": None,
    "debianutils": None,
}


def log(msg: str) -> None:
    print(f"  {msg}", file=sys.stderr)


# Cloudflare rejects the default python-urllib UA with 403.
_UA = "PAI-Android-Bundler/1.0 (+https://github.com/termux)"


def _open(url: str, timeout: int = 60):
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    return urllib.request.urlopen(req, timeout=timeout)


def fetch_packages_index() -> str:
    log(f"Fetching {PACKAGES_URL}")
    with _open(PACKAGES_URL, timeout=60) as r:
        raw = r.read()
    return gzip.decompress(raw).decode("utf-8", errors="replace")


def parse_stanzas(text: str) -> list[dict]:
    stanzas = []
    for block in text.split("\n\n"):
        if not block.strip():
            continue
        fields: dict[str, str] = {}
        key = None
        for line in block.splitlines():
            if not line:
                continue
            if line[0].isspace():
                if key:
                    fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, _, val = line.partition(":")
                fields[key.strip()] = val.strip()
        if "Package" in fields:
            stanzas.append(fields)
    return stanzas


def load_repo_index() -> dict[str, dict]:
    """Returns {pkg_name: fields}. If multiple versions, keeps the last (newest)."""
    text = fetch_packages_index()
    idx: dict[str, dict] = {}
    for s in parse_stanzas(text):
        idx[s["Package"]] = s
    log(f"Loaded {len(idx)} packages from {ARCH} repo")
    return idx


def load_bootstrap_installed() -> set[str]:
    log(f"Reading {BOOTSTRAP_ZIP.name} dpkg status")
    with zipfile.ZipFile(BOOTSTRAP_ZIP) as z:
        status = z.read("var/lib/dpkg/status").decode("utf-8", errors="replace")
    installed = {s["Package"] for s in parse_stanzas(status) if "Package" in s}
    # Also include Provides from installed packages (virtual packages they satisfy)
    for s in parse_stanzas(status):
        for prov in parse_deplist(s.get("Provides", "")):
            installed.add(prov)
    log(f"Bootstrap has {len(installed)} installed/provided packages")
    return installed


_DEP_TOKEN_RE = re.compile(r"^\s*([a-zA-Z0-9][a-zA-Z0-9+.\-]*)")


def parse_deplist(field: str) -> list[str]:
    """Parse a Depends-style field into package names. Picks first alternative."""
    out = []
    if not field:
        return out
    for clause in field.split(","):
        first_alt = clause.split("|")[0]
        m = _DEP_TOKEN_RE.match(first_alt)
        if m:
            out.append(m.group(1))
    return out


def resolve_closure(
    roots: list[str],
    repo: dict[str, dict],
    installed: set[str],
) -> list[str]:
    """Breadth-first dep closure. Skips packages satisfied by bootstrap."""
    out: list[str] = []
    seen: set[str] = set()
    queue = list(roots)
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        seen.add(name)
        if name in VIRTUAL_PROVIDERS:
            prov = VIRTUAL_PROVIDERS[name]
            if prov is None:
                continue
            name = prov
        if name in installed:
            continue
        if name not in repo:
            log(f"  WARN: {name} not in repo index — skipping")
            continue
        out.append(name)
        fields = repo[name]
        deps = parse_deplist(fields.get("Depends", ""))
        # Also include Pre-Depends (must be installed before the package itself)
        deps += parse_deplist(fields.get("Pre-Depends", ""))
        for d in deps:
            if d not in seen:
                queue.append(d)
    return out


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def download(pkg: dict, out_dir: Path, clean: bool) -> Path:
    filename = Path(pkg["Filename"]).name
    out = out_dir / filename
    expected_sha = pkg.get("SHA256", "")
    if out.exists() and not clean:
        if expected_sha and sha256_file(out) == expected_sha:
            log(f"  [cached] {filename}")
            return out
        log(f"  [stale]  {filename} — redownloading")
    url = f"{MIRROR}/{pkg['Filename']}"
    log(f"  [get]    {filename}  ({int(pkg.get('Size', 0)) // 1024} KB)")
    tmp = out.with_suffix(out.suffix + ".part")
    with _open(url, timeout=120) as r, tmp.open("wb") as f:
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)
    if expected_sha:
        actual = sha256_file(tmp)
        if actual != expected_sha:
            tmp.unlink()
            raise RuntimeError(f"Checksum mismatch for {filename}: {actual} != {expected_sha}")
    tmp.rename(out)
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true", help="Force-redownload all packages")
    parser.add_argument("--roots", nargs="*", default=ROOT_PACKAGES,
                        help=f"Override root packages (default: {' '.join(ROOT_PACKAGES)})")
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    repo = load_repo_index()
    installed = load_bootstrap_installed()
    closure = resolve_closure(args.roots, repo, installed)

    log(f"Resolved closure: {len(closure)} packages to bundle")
    for name in closure:
        v = repo[name].get("Version", "?")
        sz = int(repo[name].get("Installed-Size", 0))
        log(f"  - {name} ({v})  {sz} KB installed")

    # Clean stale .debs that are no longer in the closure
    wanted_names = {Path(repo[n]["Filename"]).name for n in closure}
    for existing in OUT_DIR.glob("*.deb"):
        if existing.name not in wanted_names:
            log(f"  [prune]  {existing.name}")
            existing.unlink()

    # Download
    total_bytes = 0
    manifest_lines = []
    for name in closure:
        pkg = repo[name]
        path = download(pkg, OUT_DIR, args.clean)
        size = path.stat().st_size
        total_bytes += size
        manifest_lines.append(
            f"{path.name}\t{name}\t{pkg.get('Version', '?')}\t{size}"
        )

    # Write manifest
    header = "# filename\tpackage\tversion\tsize_bytes\n"
    MANIFEST.write_text(header + "\n".join(manifest_lines) + "\n")

    log(f"Done. Bundled {len(closure)} .debs = {total_bytes / 1024 / 1024:.1f} MB")
    log(f"Manifest: {MANIFEST.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
