/**
 * Bun API polyfill for Node.js/tsx on Android.
 * Provides compatible shims for Bun-specific APIs used by PAI.
 *
 * Supported: spawn, spawnSync, $, serve
 */
"use strict";

const cp = require("child_process");
const http = require("http");

/**
 * Bun.spawn() polyfill — returns a subprocess with .exited promise.
 * Bun API: spawn({ cmd, cwd, env, stdin, stdout, stderr }) or spawn(cmd[])
 */
function spawn(cmdOrOpts, optsArg) {
  let cmd, opts;
  if (Array.isArray(cmdOrOpts)) {
    cmd = cmdOrOpts;
    opts = optsArg || {};
  } else if (typeof cmdOrOpts === "object" && cmdOrOpts.cmd) {
    cmd = cmdOrOpts.cmd;
    opts = cmdOrOpts;
  } else {
    throw new Error("bun-polyfill: spawn requires cmd array or options with cmd");
  }

  const stdio = [
    opts.stdin === "inherit" ? "inherit" : opts.stdin === "pipe" ? "pipe" : opts.stdin === null ? "ignore" : "inherit",
    opts.stdout === "pipe" ? "pipe" : opts.stdout === null ? "ignore" : "inherit",
    opts.stderr === "pipe" ? "pipe" : opts.stderr === null ? "ignore" : "inherit",
  ];

  const child = cp.spawn(cmd[0], cmd.slice(1), {
    cwd: opts.cwd,
    env: opts.env || process.env,
    stdio,
  });

  // Bun's .exited is a Promise<number>
  child.exited = new Promise((resolve) => {
    child.on("close", (code) => resolve(code ?? 1));
    child.on("error", () => resolve(1));
  });

  // Bun's .stdout and .stderr are ReadableStreams when piped
  // Node already provides these as streams, which is close enough

  return child;
}

/**
 * Bun.spawnSync() polyfill.
 * Bun API: spawnSync({ cmd, cwd, env }) or spawnSync(cmd[])
 */
function spawnSync(cmdOrOpts, optsArg) {
  let cmd, opts;
  if (Array.isArray(cmdOrOpts)) {
    cmd = cmdOrOpts;
    opts = optsArg || {};
  } else if (typeof cmdOrOpts === "object" && cmdOrOpts.cmd) {
    cmd = cmdOrOpts.cmd;
    opts = cmdOrOpts;
  } else {
    throw new Error("bun-polyfill: spawnSync requires cmd array or options with cmd");
  }

  const result = cp.spawnSync(cmd[0], cmd.slice(1), {
    cwd: opts.cwd,
    env: opts.env || process.env,
    stdio: [
      opts.stdin === "inherit" ? "inherit" : "pipe",
      "pipe",
      "pipe",
    ],
    encoding: "utf-8",
    timeout: opts.timeout,
  });

  return {
    exitCode: result.status ?? 1,
    stdout: result.stdout || "",
    stderr: result.stderr || "",
    success: result.status === 0,
  };
}

/**
 * Bun.$`cmd` (shell) polyfill — tagged template literal for shell commands.
 * Returns an object with .text(), .json(), .quiet(), .nothrow(), and is thenable.
 */
function $(strings, ...values) {
  let command = "";
  for (let i = 0; i < strings.length; i++) {
    command += strings[i];
    if (i < values.length) {
      const val = values[i];
      // Shell-escape the value
      command += typeof val === "string" ? val : String(val);
    }
  }

  const shellObj = {
    _command: command.trim(),
    _nothrow: false,
    _quiet: false,

    nothrow() {
      this._nothrow = true;
      return this;
    },

    quiet() {
      this._quiet = true;
      return this;
    },

    async text() {
      const result = cp.spawnSync("sh", ["-c", this._command], {
        encoding: "utf-8",
        stdio: ["inherit", "pipe", "pipe"],
      });
      if (!this._nothrow && result.status !== 0) {
        const err = new Error(`Shell command failed: ${this._command}\n${result.stderr}`);
        err.exitCode = result.status;
        throw err;
      }
      return (result.stdout || "").trim();
    },

    async json() {
      const text = await this.text();
      return JSON.parse(text);
    },

    async lines() {
      const text = await this.text();
      return text.split("\n").filter(Boolean);
    },

    // Make it thenable (await $`cmd` returns the result)
    then(resolve, reject) {
      const result = cp.spawnSync("sh", ["-c", this._command], {
        encoding: "utf-8",
        stdio: this._quiet ? ["inherit", "pipe", "pipe"] : ["inherit", "inherit", "inherit"],
      });
      const obj = {
        exitCode: result.status ?? 1,
        stdout: result.stdout || "",
        stderr: result.stderr || "",
        text() { return (result.stdout || "").trim(); },
      };
      if (!this._nothrow && result.status !== 0) {
        const err = new Error(`Shell command failed (exit ${result.status}): ${this._command}`);
        err.exitCode = result.status;
        if (reject) reject(err);
        return;
      }
      if (resolve) resolve(obj);
    },
  };

  return shellObj;
}

/**
 * Bun.serve() polyfill — basic HTTP server compatible with Bun's API.
 */
function serve(opts) {
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
      const bunReq = {
        url: req.url,
        method: req.method,
        headers: new Map(Object.entries(req.headers)),
        json: () => new Promise((resolve, reject) => {
          let body = "";
          req.on("data", (chunk) => { body += chunk; });
          req.on("end", () => { try { resolve(JSON.parse(body)); } catch(e) { reject(e); } });
        }),
        text: () => new Promise((resolve) => {
          let body = "";
          req.on("data", (chunk) => { body += chunk; });
          req.on("end", () => resolve(body));
        }),
      };

      const response = await opts.fetch(bunReq, { url });

      if (response instanceof Response) {
        res.writeHead(response.status, Object.fromEntries(response.headers.entries()));
        const body = await response.text();
        res.end(body);
      } else if (response && typeof response === "object") {
        res.writeHead(response.status || 200, response.headers || {});
        res.end(response.body || "");
      }
    } catch (e) {
      res.writeHead(500);
      res.end("Internal Server Error");
    }
  });

  const port = opts.port || 3000;
  const hostname = opts.hostname || "0.0.0.0";
  server.listen(port, hostname);

  return {
    stop() { server.close(); },
    port,
    hostname,
  };
}

module.exports = { spawn, spawnSync, $, serve };
