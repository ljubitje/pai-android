# PAI Native Integration Plan — Android Terminal App

**Date:** 2026-03-23
**Approach:** Install PAI natively from official repo, debug issues incrementally
**Strategy:** Hybrid (Option C) — Direct Termux for everything, proot wrapper only for `claude` command

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│  PAI Android App (Kotlin)                                │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Terminal Emulator (Termux libraries)               │  │
│  │  ┌──────────────────────────────────────────────┐  │  │
│  │  │  Bash Shell (Termux Bootstrap)               │  │  │
│  │  │                                              │  │  │
│  │  │  ┌─────────────────────────────────────────┐ │  │  │
│  │  │  │  Claude Code CLI                        │ │  │  │
│  │  │  │  (npm install, launched via proot -b     │ │  │  │
│  │  │  │   for /tmp bind mount)                  │ │  │  │
│  │  │  │                                         │ │  │  │
│  │  │  │  ~/.claude/  ← PAI config, skills,      │ │  │  │
│  │  │  │               hooks, memory, algorithm  │ │  │  │
│  │  │  │                                         │ │  │  │
│  │  │  │  PAI Tools (bun or node fallback)       │ │  │  │
│  │  │  └─────────────────────────────────────────┘ │  │  │
│  │  │                                              │  │  │
│  │  │  pkg/apt packages: git, nodejs, curl, etc.   │  │  │
│  │  └──────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

**Key insight:** PAI is fundamentally Claude Code + configuration files. The entire skill/hook/algorithm/memory system is just files that Claude Code reads. If Claude Code runs on Termux, PAI is ~95% portable by cloning the repo into `~/.claude/`.

---

## Known Blockers & Workarounds (from research)

| Blocker | Severity | Workaround | Source |
|---------|----------|------------|--------|
| Claude Code hardcodes `/tmp/claude/` paths | HIGH | `proot -b $PREFIX/tmp:/tmp claude` | GitHub #15637, #18342 |
| Claude Code bundled Bun crashes on Android | HIGH | May need to patch/replace bundled Bun, or use older CC version | GitHub #12160 |
| ARM64 "double free or corruption" in CC 1.0.51+ | MEDIUM | Try downgrading, or test latest (may be fixed) | GitHub #12160 |
| Bun not officially supported on Android | MEDIUM | Use bun-on-termux (glibc-runner) OR fall back to Node.js | bun #8685 |
| CC interactive mode hangs with Node.js v24 | MEDIUM | Use Node.js LTS (v22) instead | GitHub #23634 |
| CC native installer misdetects aarch64 as "arm" | LOW | Install via `npm install -g` instead of native installer | GitHub #3569 |
| CC background agents fail due to /tmp | LOW | proot wrapper handles this | GitHub #15628 |
| OAuth flow issues on headless ARM64 | LOW | Use API key auth instead of OAuth | GitHub #27405 |

---

## Phase 1: Runtime Foundation
**Goal:** Get Node.js, git, and basic tools working in the app's Termux environment
**Estimated effort:** Small — mostly already done

### Tasks
1. **Verify existing bootstrap** has `git`, `curl`, `nodejs` (or add to first-run apt install)
   - Modify `profile.d/first-run-update.sh` or bashrc to `pkg install git nodejs-lts curl` on first boot
2. **Pin Node.js to LTS (v22)** — avoid v24 which causes Claude Code hangs
   - `pkg install nodejs-lts` instead of `nodejs`
3. **Install proot** — needed for /tmp bind mount
   - `pkg install proot`
4. **Verify $HOME and $PREFIX paths** are correct for ~/.claude/ to work
   - `$HOME` should be writable and persistent across app restarts
   - Currently `$HOME = $EXTERNAL_STORAGE_DIR` — may need to reconsider for ~/.claude/ persistence

### Success criteria
- [ ] `node --version` shows v22.x in app terminal
- [ ] `git --version` works
- [ ] `proot --version` works
- [ ] `~/.claude/` directory is writable and persists across app restarts

### Potential issues
- **$HOME points to external storage** — currently set to external storage dir in MainActivity. PAI expects `~/.claude/` to be in `$HOME`. If external storage has permission restrictions, may need to use app's private data dir instead, or make HOME configurable.

---

## Phase 2: Claude Code CLI Installation
**Goal:** Get `claude` command running in the terminal
**Estimated effort:** Medium — this is where most debugging will happen

### Tasks
1. **Install Claude Code via npm** (NOT native installer — it misdetects ARM):
   ```bash
   npm install -g @anthropic-ai/claude-code
   ```
2. **Create proot wrapper script** at `$PREFIX/bin/claude-wrapper`:
   ```bash
   #!/data/data/kle.ljubitje.pai/files/usr/bin/bash
   # Wrapper: bind-mounts /tmp so Claude Code can write there
   exec proot -b "$PREFIX/tmp:/tmp" claude "$@"
   ```
3. **Alias claude to the wrapper** in bashrc:
   ```bash
   alias claude='claude-wrapper'
   ```
4. **Test basic functionality**:
   ```bash
   claude --version     # Should work without proot
   claude --help        # Should work without proot
   claude-wrapper       # Interactive mode — needs proot
   ```
5. **Handle ARM64 crash** if it occurs:
   - Try latest version first (crash may be fixed)
   - If crashes: try `npm install -g @anthropic-ai/claude-code@0.2.114` (last known-good ARM64)
   - If still crashes: investigate if it's the bundled Bun — may need to replace it with system Node.js
6. **Set up authentication**:
   - Use API key method: `export ANTHROPIC_API_KEY=sk-ant-...` in bashrc
   - Avoid OAuth (known issues on headless ARM64)

### Success criteria
- [ ] `claude --version` outputs version number
- [ ] `claude-wrapper` launches interactive session
- [ ] Claude can respond to a simple prompt
- [ ] Session doesn't crash or hang

### Potential issues
- **Bundled Bun binary:** Claude Code bundles its own Bun. If this binary crashes on Android ARM64, we may need to:
  1. Find the bundled binary path (inside node_modules/@anthropic-ai/claude-code/)
  2. Replace it with a working alternative (system node, or bun-on-termux)
  3. Or use an environment variable if CC supports runtime override

---

## Phase 3: PAI Repository & Configuration
**Goal:** Install PAI from official repo into ~/.claude/
**Estimated effort:** Medium

### Tasks
1. **Clone PAI repo** (or the installer portion):
   ```bash
   # Option A: Full clone if repo is available
   git clone <pai-repo-url> ~/.claude/PAI-source

   # Option B: Copy from desktop installation (if syncing)
   # rsync -av desktop:~/.claude/ ~/.claude/
   ```
2. **Run PAI installer in CLI mode**:
   ```bash
   cd ~/.claude/PAI-Install
   node main.ts --mode cli
   ```
   - Note: Installer uses `bun`, may need to modify to use `node` or install bun-on-termux first
   - If bun required: install via bun-on-termux project (glibc-runner approach)
3. **Walk through installer steps** — debug each:
   - Step 1 (System Detection): May misidentify Android/Termux — patch detect.ts if needed
   - Step 2 (Prerequisites): Will look for bun, git, claude — handle missing bun
   - Step 3 (API Keys): Enter ElevenLabs key (or skip voice)
   - Step 4 (Identity): Configure name, AI name, timezone
   - Step 5 (Repository): Clone PAI into ~/.claude/
   - Step 6 (Configuration): Generate settings.json — may need path adjustments
   - Step 7 (Voice): Skip or configure (localhost:8888 voice server may not work on Android)
   - Step 8 (Validation): Verify installation
4. **Fix path issues** in generated configs:
   - settings.json paths must use Android-compatible paths
   - Hook handler paths must resolve correctly
   - Shebang lines may need patching (#!/usr/bin/env → Termux equivalent)

### Success criteria
- [ ] ~/.claude/ directory structure matches desktop PAI layout
- [ ] settings.json exists with valid configuration
- [ ] CLAUDE.md is generated
- [ ] Skills directory populated
- [ ] Hooks directory populated

### Potential issues
- **Installer uses bun runtime**: The `main.ts` has a bun shebang. Options:
  1. Install bun-on-termux and use it
  2. Run with `node --experimental-modules main.ts` (may need minor TS→JS conversion)
  3. Run `npx tsx main.ts --mode cli` (tsx provides TypeScript execution on Node)
- **Path assumptions**: Installer assumes `$HOME/.claude/` which should work if HOME is set correctly
- **Shebang lines**: Many tool .ts files have `#!/path/to/bun` shebangs — need patching to Termux paths

---

## Phase 4: Bun Runtime (for PAI Tools)
**Goal:** Get bun or a compatible alternative running for PAI's TypeScript tools
**Estimated effort:** Medium

### Strategy: Try bun first, fall back to Node.js + tsx

### Tasks
1. **Try bun-on-termux**:
   ```bash
   # Option A: glibc-runner approach
   git clone https://github.com/tribixbite/bun-on-termux
   # Follow instructions

   # Option B: Direct approach
   git clone https://github.com/Happ1ness-dev/bun-termux
   # Follow instructions
   ```
   - Must use `BUN_OPTIONS="--backend=copyfile"` for install operations
2. **If bun fails, set up Node.js alternative**:
   ```bash
   npm install -g tsx  # TypeScript execution for Node.js
   ```
   - Create wrapper: `$PREFIX/bin/bun` that redirects to `tsx`:
     ```bash
     #!/data/data/kle.ljubitje.pai/files/usr/bin/bash
     exec tsx "$@"
     ```
   - This lets PAI tool shebangs (`#!/usr/bin/env bun`) resolve to tsx
3. **Test key PAI tools**:
   ```bash
   bun run ~/.claude/PAI/Tools/BuildCLAUDE.ts
   bun run ~/.claude/PAI/Tools/Banner.ts
   bun run ~/.claude/PAI/Tools/SessionProgress.ts
   ```

### Success criteria
- [ ] `bun --version` works (native or via wrapper)
- [ ] PAI TypeScript tools execute without error
- [ ] BuildCLAUDE.ts generates valid CLAUDE.md

### Potential issues
- **bun vs tsx API differences**: Some tools may use bun-specific APIs (Bun.file, Bun.serve, etc.) that don't exist in Node.js. These would need adaptation.
- **Import resolution**: Bun and Node.js handle TypeScript imports slightly differently

---

## Phase 5: PAI Hooks & Skills Verification
**Goal:** Verify Claude Code loads PAI hooks and skills correctly
**Estimated effort:** Small-Medium

### Tasks
1. **Launch claude with PAI**:
   ```bash
   claude-wrapper   # In project directory
   ```
2. **Verify CLAUDE.md loads** — check that the Algorithm, modes, and routing table appear
3. **Verify hooks fire**:
   - SessionStart hook should load dynamic context
   - Check for errors in Claude Code's debug output
4. **Verify skills activate**:
   - Try invoking a simple skill (e.g., "/commit" or asking for research)
5. **Fix hook handler paths** if they fail:
   - Hook handlers are .ts files — need bun or tsx to execute
   - settings.json hook entries point to handler files — verify paths resolve

### Success criteria
- [ ] Claude Code session starts with PAI context loaded
- [ ] SessionStart hook fires (banner/dynamic context appears)
- [ ] At least one skill can be invoked
- [ ] Hooks don't crash the session

### Potential issues
- **Hook execution**: Hooks run TypeScript via bun — if bun isn't available, hooks will fail silently or error. Need the bun/tsx wrapper from Phase 4.
- **settings.json paths**: May have desktop-specific paths that need updating

---

## Phase 6: Android App Integration
**Goal:** Make the Kotlin app PAI-aware for smoother UX
**Estimated effort:** Medium-Large (future work)

### Tasks
1. **Auto-install PAI dependencies on first boot**:
   - Extend `BootstrapInstaller.kt` or first-run script to install: `nodejs-lts git proot curl`
   - After bootstrap completes, run PAI prerequisite installation
2. **API key management UI** (optional, future):
   - Secure storage for ANTHROPIC_API_KEY
   - Android Keystore integration
   - First-run prompt for API key
3. **PAI-specific extra keys**:
   - Add keys to ExtraKeysView: `/` (for slash commands), common PAI shortcuts
4. **Session persistence**:
   - Ensure ~/.claude/MEMORY/ survives app updates
   - Consider backup/restore mechanism
5. **Notification bridge** (optional, future):
   - Bridge ElevenLabs voice notifications to Android notifications
   - Or run a lightweight voice server on localhost

### Success criteria
- [ ] Fresh app install → PAI ready after bootstrap completes
- [ ] API key stored securely
- [ ] PAI sessions persist across app lifecycle events

---

## Phase Dependency Graph

```
Phase 1 (Runtime Foundation)
    │
    ▼
Phase 2 (Claude Code CLI)
    │
    ├──────────────────┐
    ▼                  ▼
Phase 3 (PAI Repo)    Phase 4 (Bun Runtime)
    │                  │
    └────────┬─────────┘
             ▼
    Phase 5 (Hooks & Skills)
             │
             ▼
    Phase 6 (App Integration)
```

---

## Immediate Next Steps (What to do first)

1. **SSH into the Android device** running the PAI terminal app
2. **Check what's already installed**: `which node git curl proot`
3. **Install missing packages**: `pkg install nodejs-lts git proot`
4. **Try npm install of Claude Code**: `npm install -g @anthropic-ai/claude-code`
5. **Test with proot wrapper**: `proot -b $PREFIX/tmp:/tmp claude --version`
6. **Debug whatever breaks** — the first error tells us the real state of things

---

## Risk Assessment

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Claude Code bundled Bun crashes on ARM64 | Blocks everything | HIGH | Downgrade CC, replace bundled Bun, or run under proot-distro |
| PAI tools need bun-specific APIs | Blocks tools | MEDIUM | Audit which tools use Bun.* APIs, create shims or port to Node |
| $HOME path causes persistence issues | Data loss | MEDIUM | Test early, fix HOME to stable writable location |
| Hook handlers fail silently | Degraded PAI | MEDIUM | Check hook logs, ensure tsx/bun wrapper works |
| Claude Code version too old for PAI | Incompatible | LOW | PAI may require recent CC features — test compatibility |

---

## What Changes in Existing Kotlin Code

| File | Change | Phase |
|------|--------|-------|
| `BootstrapInstaller.kt` | Add post-bootstrap package installation (nodejs-lts, git, proot) | 1 |
| `assets/bashrc` | Add claude-wrapper alias, ANTHROPIC_API_KEY export, bun PATH | 2-4 |
| `assets/profile` | Ensure PATH includes npm global bin dir | 1 |
| `MainActivity.kt` | Possibly adjust HOME env var for ~/.claude/ persistence | 1 |
| `ExtraKeysView.kt` | Add PAI-useful keys (optional, Phase 6) | 6 |
| New: `assets/pai-setup.sh` | First-run PAI installation script | 3 |
