#!/bin/sh
# Patch PAI installer: detect shell from $SHELL instead of hardcoding .zshrc
# (Ports pai-fork commits 6158ffd and 13983e3)
set -e
BASE="$1"
[ -d "$BASE/PAI-Install" ] || exit 0

VT="$BASE/PAI-Install/engine/validate.ts"
if [ -f "$VT" ] && grep -q 'Zsh alias' "$VT"; then
  sed -i \
    -e 's|// 8. Zsh alias configured|// 8. Shell alias configured|' \
    -e 's|const zshrcPath = .*|const userShell = process.env.SHELL || "/bin/zsh"; const rcFile = userShell.includes("bash") ? ".bashrc" : userShell.includes("fish") ? ".config/fish/config.fish" : ".zshrc"; const rcPath = join(homedir(), rcFile);|' \
    -e 's|existsSync(zshrcPath)|existsSync(rcPath)|' \
    -e 's|const zshContent = readFileSync(zshrcPath, "utf-8");|const rcContent = readFileSync(rcPath, "utf-8");|' \
    -e 's|zshContent\.includes|rcContent.includes|' \
    -e 's|"Configured in .zshrc"|`Configured in ${rcFile}`|' \
    -e 's|"Not found.*source ~/\.zshrc"|`Not found — run: source ~/${rcFile}`|' \
    "$VT"
fi

TY="$BASE/PAI-Install/engine/types.ts"
if [ -f "$TY" ] && ! grep -q 'shellRcFile' "$TY"; then
  sed -i '/totalSteps: number;/a\  shellRcFile: string;' "$TY"
fi

# Add shellRcFile to generateSummary return
if [ -f "$VT" ] && ! grep -q 'shellRcFile' "$VT"; then
  sed -i '/totalSteps: 8,/a\    shellRcFile: ((s) => s.includes("bash") ? ".bashrc" : s.includes("fish") ? ".config/fish/config.fish" : ".zshrc")(process.env.SHELL || "/bin/zsh"),' "$VT"
fi

DI="$BASE/PAI-Install/cli/display.ts"
if [ -f "$DI" ] && grep -q 'source ~/.zshrc' "$DI"; then
  sed -i \
    -e '/source ~\/\.zshrc/i\  const rcCmd = `source ~/${summary.shellRcFile || ".zshrc"} \&\& pai`;' \
    -e 's|.*source ~/\.zshrc.*|  print(`${c.navy}║${c.reset}  ${c.lightBlue}Run: ${c.bold}${rcCmd}${c.reset}${" ".repeat(Math.max(0, 43 - rcCmd.length))}${c.navy}║${c.reset}`);|' \
    "$DI"
fi

AJ="$BASE/PAI-Install/public/app.js"
if [ -f "$AJ" ] && grep -q 'source ~/.zshrc' "$AJ"; then
  sed -i 's|source ~/.zshrc && pai|source ~/${summary.shellRcFile || ".zshrc"} \&\& pai|' "$AJ"
fi
