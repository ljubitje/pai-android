/**
 * Node.js preload fix for non-com.termux Termux-based apps.
 *
 * Termux-compiled Node.js has /data/data/com.termux/files/usr/bin/sh
 * hardcoded as the default shell for child_process. This preload
 * patches execSync/execFileSync/spawn/exec to use the correct shell
 * from $PREFIX/bin/sh or process.env.SHELL.
 */
'use strict';
const cp = require('child_process');
const fs = require('fs');

const correctShell = (function() {
  // Try PREFIX-based shell first
  const prefix = process.env.PREFIX;
  if (prefix) {
    const candidates = [prefix + '/bin/sh', prefix + '/bin/bash'];
    for (const c of candidates) {
      try { if (fs.statSync(c).isFile()) return c; } catch {}
    }
  }
  // Fall back to SHELL env
  if (process.env.SHELL) {
    try { if (fs.statSync(process.env.SHELL).isFile()) return process.env.SHELL; } catch {}
  }
  return '/system/bin/sh';
})();

// Patch execSync
const origExecSync = cp.execSync;
cp.execSync = function(cmd, opts) {
  opts = Object.assign({}, opts);
  if (!opts.shell) opts.shell = correctShell;
  return origExecSync.call(this, cmd, opts);
};

// Patch exec
const origExec = cp.exec;
cp.exec = function(cmd, opts, cb) {
  if (typeof opts === 'function') { cb = opts; opts = {}; }
  opts = Object.assign({}, opts);
  if (!opts.shell) opts.shell = correctShell;
  return origExec.call(this, cmd, opts, cb);
};

// Patch spawnSync (when shell: true)
const origSpawnSync = cp.spawnSync;
cp.spawnSync = function(cmd, args, opts) {
  if (opts && opts.shell === true) {
    opts = Object.assign({}, opts, { shell: correctShell });
  }
  return origSpawnSync.call(this, cmd, args, opts);
};

// Patch spawn (when shell: true)
const origSpawn = cp.spawn;
cp.spawn = function(cmd, args, opts) {
  if (opts && opts.shell === true) {
    opts = Object.assign({}, opts, { shell: correctShell });
  }
  return origSpawn.call(this, cmd, args, opts);
};
