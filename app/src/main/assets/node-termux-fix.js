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
const dns = require('dns');

// Android apps can't read /etc/resolv.conf — set DNS servers explicitly
// and override dns.lookup to use the JS resolver (which respects setServers)
// instead of the OS getaddrinfo (which reads /etc/resolv.conf).
try {
  dns.setServers(['8.8.8.8', '8.8.4.4', '1.1.1.1']);

  const origLookup = dns.lookup;
  dns.lookup = function(hostname, options, callback) {
    // Normalize arguments (options is optional)
    if (typeof options === 'function') {
      callback = options;
      options = {};
    }
    if (typeof options === 'number') {
      options = { family: options };
    }
    options = options || {};

    // For localhost / numeric IPs, use original
    if (hostname === 'localhost' || /^\d+\.\d+\.\d+\.\d+$/.test(hostname) || hostname === '::1') {
      return origLookup.call(dns, hostname, options, callback);
    }

    // Use dns.resolve4/resolve6 which go through the JS resolver
    const family = options.family || 0;
    if (family === 6) {
      dns.resolve6(hostname, (err, addresses) => {
        if (err) return callback(err);
        callback(null, addresses[0], 6);
      });
    } else {
      dns.resolve4(hostname, (err, addresses) => {
        if (err) {
          // Fall back to IPv6 if no family preference
          if (family === 0) {
            dns.resolve6(hostname, (err6, addr6) => {
              if (err6) return callback(err);
              callback(null, addr6[0], 6);
            });
          } else {
            callback(err);
          }
        } else {
          callback(null, addresses[0], 4);
        }
      });
    }
  };
} catch (e) {
  // Ignore — non-critical if DNS is already working
}

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
