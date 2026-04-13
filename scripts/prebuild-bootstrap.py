#!/usr/bin/env python3
"""
Pre-patch the Termux bootstrap at BUILD time.

Moves the expensive path-rewriting step (com.termux → kle.ljubitje.pai) from
runtime (BootstrapInstaller.patchTermuxPaths scanning bin/ + etc/ on a cold
filesystem) to build time (one pass on the developer/CI machine).

Reads:  app/src/main/assets/bootstrap-aarch64.zip
Writes: app/src/main/assets/bootstrap-aarch64.zip  (in-place, same name)
        app/src/main/assets/bootstrap-aarch64.zip.patched-stamp
            — sentinel file so the Gradle task can skip re-running if
              inputs haven't changed.

Behavior is a 1:1 port of BootstrapInstaller.patchTermuxPaths():
  - scans files under bin/ and etc/
  - skips ELF binaries (detected by 0x7F 'ELF' magic)
  - skips files >512 KB
  - replaces /data/data/com.termux/cache → /data/data/<pkg>/cache
  - replaces /data/data/com.termux/files → /data/data/<pkg>/files
  - the home dir substitution (home → $HOME) is performed by this script

IMPORTANT: the runtime still needs to handle .deb postinst scripts (which
ship inside .deb files, not in the bootstrap itself) — that wrapper stays.
"""

import argparse
import io
import shutil
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ZIP = REPO_ROOT / "app/src/main/assets/bootstrap-aarch64.zip"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", default="kle.ljubitje.pai",
                    help="Android applicationId to rewrite paths to")
    ap.add_argument("--zip", type=Path, default=DEFAULT_ZIP,
                    help="Path to bootstrap zip (patched in place)")
    args = ap.parse_args()

    pkg = args.package
    zip_path: Path = args.zip
    stamp_path = zip_path.with_suffix(zip_path.suffix + ".patched-stamp")

    termux_files = "/data/data/com.termux/files"
    termux_cache = "/data/data/com.termux/cache"
    app_files = f"/data/data/{pkg}/files"
    app_cache = f"/data/data/{pkg}/cache"
    app_home = f"{app_files}/home"

    if not zip_path.exists():
        print(f"ERROR: bootstrap zip not found: {zip_path}", file=sys.stderr)
        return 1

    # Idempotent: stamp file records the package name we patched for. If the
    # stamp matches and the zip is newer than neither itself nor this script,
    # the Gradle task will skip us. We still re-run if this script changes.
    if stamp_path.exists() and stamp_path.read_text().strip() == pkg:
        print(f"  bootstrap already patched for {pkg}; skipping")
        return 0

    print(f"  Patching {zip_path.name} for package {pkg}")

    # Read everything into memory, rewrite, write to temp, swap
    src = zipfile.ZipFile(zip_path, "r")
    tmp_path = zip_path.with_suffix(zip_path.suffix + ".patching")
    dst = zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED)

    patched = 0
    scanned = 0
    for info in src.infolist():
        data = src.read(info.filename)

        # Only scan files under bin/ or etc/; mirror the runtime logic
        is_text_candidate = (
            info.filename.startswith(("bin/", "etc/"))
            and not info.is_dir()
            and not info.filename.endswith(".so")
            and info.file_size < 512_000
        )
        if is_text_candidate:
            scanned += 1
            # Skip ELF binaries
            if len(data) >= 4 and data[:4] == b"\x7fELF":
                pass
            elif termux_files.encode() in data or termux_cache.encode() in data:
                try:
                    text = data.decode("utf-8")
                    text = text.replace(termux_cache, app_cache)
                    text = text.replace(termux_files, app_files)
                    # After replacing termux_files, references to the hardcoded
                    # home dir become absolute references into app-files/home.
                    # The runtime used to convert these to $HOME; do the same.
                    text = text.replace(app_home, "$HOME")
                    data = text.encode("utf-8")
                    patched += 1
                except UnicodeDecodeError:
                    pass  # not a text file, leave as-is

        # Preserve original metadata (timestamps, permissions)
        new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        new_info.external_attr = info.external_attr
        new_info.compress_type = info.compress_type
        dst.writestr(new_info, data)

    src.close()
    dst.close()

    # Atomic swap
    shutil.move(str(tmp_path), str(zip_path))
    stamp_path.write_text(pkg + "\n")

    print(f"  Scanned {scanned} candidates in bin/ + etc/; patched {patched}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
