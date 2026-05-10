# APAI patch series for bundled PAI v5.0.0

Patches applied to the upstream PAI release tarball
(`Releases/v5.0.0/.claude/` from `danielmiessler/Personal_AI_Infrastructure`)
at APK-build time by `scripts/build-bundled-pai.sh`.

Each `.patch` file is unified-diff format with a multi-paragraph header
explaining bug, root cause, fix scope, and references to upstream issues.
Numerical filename order is application order; `patch -p1` from the repo root.

## Origin

All `0001`–`0016` patches are vendored from
[`ljubitje/pai-nix`](https://codeberg.org/ljubitje/pai-nix) at the
patches/ subtree (`pkgs/tools/misc/personal-ai-infrastructure/patches/`).

pai-nix has 21 patches; APAI vendors the subset that applies on Android.
The 5 excluded patches are Nix-specific or Linux-desktop-specific:

| pai-nix # | Title                                              | Reason excluded                                    |
| --------- | -------------------------------------------------- | -------------------------------------------------- |
| 0001      | Skip bun management on Nix-built installs          | Nix-only                                           |
| 0004      | Nix installer fixes                                | Nix-only                                           |
| 0005      | NIX_STORE → PAI_NIX_INSTALL runtime gate           | Nix-only                                           |
| 0020      | Clear `.pai-installing` at install_complete        | pai-nix wrapper concept                            |
| 0021      | systemd user unit for Pulse on Linux               | Android has no systemd-user                        |

## Mapping (pai-nix # → APAI #)

| APAI # | pai-nix # | Title                                              |
| ------ | --------- | -------------------------------------------------- |
| 0001   | 0002      | Add Linux support to Pulse manage.sh               |
| 0002   | 0006      | Fix Pulse case-sensitive path construction         |
| 0003   | 0003      | Add Pulse `package.json`                           |
| 0004   | 0019      | Install Pulse deps before manage.sh                |
| 0005   | 0008      | Fix installer paiDir misnaming                     |
| 0006   | 0009      | Fix remaining mixed-case paths (TOOLS, ALGORITHM)  |
| 0007   | 0010      | Fix installer `${HOME}` literal expansion          |
| 0008   | 0011      | Substitute placeholders in PAI_SYSTEM_PROMPT.md    |
| 0009   | 0007      | Fix prompt classifier slash-prefix                 |
| 0010   | 0012      | Register ISASync/CheckpointPerISC/ToolFailureTracker hooks |
| 0011   | 0014      | Fix RepeatDetection state-save timing              |
| 0012   | 0015      | Add root `package.json` for PAI runtime deps       |
| 0013   | 0013      | Fix `GenerateTelosSummary.ts` parser bugs          |
| 0014   | 0016      | Add `PAI_STATE.json` producer                      |
| 0015   | 0017      | Migrate observability to /interview TELOS schema   |
| 0016   | 0018      | Emit step_skip event for Telegram skip paths       |

## APAI-side modifications

### 0006 — `actions.ts` hunk re-derived

pai-nix `0009` was originally written against the post-`0005` (NIX_STORE gate)
state, in which the `aliasLine` block sits inside a `PAI_NIX_INSTALL` `else`
branch and is therefore 4-space indented. APAI does not apply pai-nix's
Nix-gate patches, so the same block is at its pristine 2-space indent at the
moment APAI 0006 runs. The `actions.ts` hunk was regenerated against APAI's
post-0005 (paiDir misnaming) state to match. The fix intent (the literal
`"Tools"` → `"TOOLS"` rename) is unchanged. The other three hunks
(`PULSE/modules/wiki.ts`, `PAI/TOOLS/pai.ts`, `PAI/TOOLS/MemoryRetriever.ts`)
were preserved from pai-nix verbatim.

## Application order matters

- `0005` (paiDir misnaming) must apply before `0006` (mixed-case paths) —
  the `actions.ts` hunk in `0006` was written against `0005`'s line layout.
- `0003` (add Pulse package.json) must apply before `0004` (install Pulse
  deps) — `bun install` needs the manifest.
- `0003` (add Pulse package.json) must apply before `0012` (root
  package.json) — `0012`'s header references the Pulse `package.json` as a
  prior sibling.

The filename order encodes the dependency order; `patch -p1` in numeric
filename order is the supported application sequence.

## Verification

After running `scripts/build-bundled-pai.sh`, the patched tree at
`/tmp/.../Releases/v5.0.0/.claude/` should:

- Contain `PAI/PULSE/package.json` (added by 0003) with 6 Pulse dependencies.
- Contain top-level `package.json` (added by 0012) with PAI runtime deps.
- Contain `PAI/USER/TELOS/PAI_STATE_INPUT.yaml` and
  `PAI/TOOLS/WritePaiState.ts` (added by 0014).
- Have zero `"Tools"` (mixed-case) literals in path-construction sites;
  all canonical `PAI/TOOLS/` references.
- Have zero `"Pulse"` mixed-case literals in path-construction sites;
  all canonical `PAI/PULSE/`.
- Have hook registrations for `ISASync`, `CheckpointPerISC`, and
  `ToolFailureTracker` in `settings.json`.
- Have no literal `{{PRINCIPAL_NAME}}`, `{{DA_NAME}}`, `{{DA_FULL_NAME}}`
  outside backtick-quoted code spans in `PAI/PAI_SYSTEM_PROMPT.md`.

A future `scripts/verify-patched-tree.sh` should encode these as automated
post-build assertions.

## Future: APAI-native shell-awareness patches (0017–00xx)

The current series stops at 0016. The legacy
`app/src/main/assets/pai-installer-patches/` directory still contains five
wholesale `.ts` replacement files written against pre-v5.0.0 PAI that
encode shell-aware bash/zsh/fish detection in the installer and PAI tool.
These need to be re-derived as proper minimal diff patches against
post-0001-0016 v5.0.0 state, then numbered 0017 onward. Once derived,
`pai-installer-patches/` and `patch-installer.sh` become obsolete and
should be deleted.
