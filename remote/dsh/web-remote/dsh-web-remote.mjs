#!/usr/bin/env node
/**
 * Runs one `dsh web` instance behind the token-injecting proxy.
 *
 * The proxy is what a reverse tunnel exposes; `dsh web` itself stays on
 * loopback and keeps printing its launch-token URL only to this runner. Logs
 * never carry the token, and the status file only carries the token-free
 * public URL, so an operator can hand the browser URL around without handing
 * out the launch credential.
 *
 * Usage:
 *   DSH_PUBLIC_URL=http://127.0.0.1:58233/ \
 *   DSH_TRUSTED_HOST=127.0.0.1:58233 \
 *   node dsh-web-remote.mjs
 *
 * Env:
 *   DSH_BIN                 dsh executable (default ~/.dsh/cli-web/node_modules/.bin/dsh)
 *   DSH_WEB_PROFILE         profile name (default web; never inherit DSH_PROFILE,
 *                           which the Desktop shell exports as its own profile)
 *   DSH_WEB_PORT            loopback port for dsh web (default 58232)
 *   DSH_PROXY_PORT          loopback port for the proxy (default 58233)
 *   DSH_PUBLIC_URL          public URL root the browser opens (required)
 *   DSH_TRUSTED_HOST        authority dsh web trusts (required)
 *   DSH_COOKIE_MAX_AGE      auth-cookie seconds kept in the browser (default 86400)
 *   DSH_LOG_DIR             state/log directory (default ~/.dsh-web-remote)
 */
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { appendFileSync, mkdirSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import readline from "node:readline";
import { createWebProxy, redactUrl } from "./proxy.mjs";

const env = process.env;
const dshBin = env.DSH_BIN ?? join(homedir(), ".dsh/cli-web/node_modules/.bin/dsh");
const profile = env.DSH_WEB_PROFILE ?? "web";
const webPort = Number(env.DSH_WEB_PORT ?? 58_232);
const proxyPort = Number(env.DSH_PROXY_PORT ?? 58_233);
const publicUrl = env.DSH_PUBLIC_URL;
const trustedHost = env.DSH_TRUSTED_HOST;
const cookieMaxAgeSeconds = Number(env.DSH_COOKIE_MAX_AGE ?? 86_400);
const logDir = env.DSH_LOG_DIR ?? join(homedir(), ".dsh-web-remote");

if (!publicUrl || !trustedHost) {
	console.error("DSH_PUBLIC_URL and DSH_TRUSTED_HOST are required");
	process.exit(2);
}
if (!Number.isInteger(webPort) || !Number.isInteger(proxyPort) || webPort === proxyPort) {
	console.error("DSH_WEB_PORT and DSH_PROXY_PORT must be distinct integers");
	process.exit(2);
}

mkdirSync(logDir, { recursive: true, mode: 0o700 });
const runnerLog = join(logDir, "runner.log");
const proxyLog = join(logDir, "proxy.log");
const statusFile = join(logDir, "status.json");
const gateSecretFile = join(logDir, "gate-secret");
let gateSecret;
try {
	gateSecret = readFileSync(gateSecretFile, "utf8").trim();
} catch {
	gateSecret = randomBytes(32).toString("hex");
	writeFileSync(gateSecretFile, `${gateSecret}\n`, { mode: 0o600 });
}
if (gateSecret === "") {
	gateSecret = randomBytes(32).toString("hex");
	writeFileSync(gateSecretFile, `${gateSecret}\n`, { mode: 0o600 });
}

function log(entry) {
	const line = JSON.stringify({ at: new Date().toISOString(), ...entry });
	appendFileSync(runnerLog, `${line}\n`, { mode: 0o600 });
	console.log(line);
}

function proxyLogLine(entry) {
	appendFileSync(proxyLog, `${JSON.stringify({ at: new Date().toISOString(), ...entry })}\n`, { mode: 0o600 });
}

let statusState = { publicUrl, webPort, proxyPort, ready: false };
function writeStatus(patch) {
	statusState = { ...statusState, ...patch };
	const temporary = `${statusFile}.tmp`;
	writeFileSync(temporary, `${JSON.stringify(statusState, null, 2)}\n`, { mode: 0o600 });
	renameSync(temporary, statusFile);
}

let proxy = null;
let child = null;
let stopping = false;
let restartTimer = null;
let readyTimer = null;

function startProxy(token) {
	if (proxy === null) {
		proxy = createWebProxy({
			upstreamPort: webPort,
			token,
			cookieMaxAgeSeconds,
			gateMaxAgeSeconds: cookieMaxAgeSeconds,
			gateSecret,
			log: proxyLogLine,
		});
		proxy.listen(proxyPort).then((address) => {
			log({ event: "proxy.listening", port: address.port });
			writeStatus({ ready: true, listening: true, updatedAt: new Date().toISOString() });
		}).catch((error) => {
			log({ event: "proxy.listen.failed", error: error.message });
			process.exitCode = 1;
		});
		return;
	}
	proxy.rotateToken(token);
}

function startWeb() {
	if (stopping) return;
	readyTimer = setTimeout(() => {
		log({ event: "web.token.timeout", webPort });
	}, 60_000);
	const args = [
		"--profile", profile,
		"--host", "127.0.0.1",
		"--port", String(webPort),
		"--public-url", publicUrl,
		"--trusted-host", trustedHost,
		"--no-open",
	];
	child = spawn(dshBin, args, { stdio: ["ignore", "pipe", "pipe"] });
	log({ event: "web.spawn", pid: child.pid, profile, webPort, publicUrl });
	const lines = readline.createInterface({ input: child.stdout });
	lines.on("line", (line) => {
		const redacted = line.replace(/([?&])token=[^&\s]+/gu, "$1token=<redacted>");
		if (redacted.trim() !== "") log({ event: "web.stdout", line: redacted });
		const match = line.match(/^dsh web:\s+(\S+)/u);
		if (match === null) return;
		let printed;
		try {
			printed = new URL(match[1]);
		} catch {
			log({ event: "web.url.unparsed", url: redactUrl(line) });
			return;
		}
		const token = printed.searchParams.get("token");
		if (token === null || token === "") {
			log({ event: "web.url.no-token", url: redactUrl(printed.href) });
			return;
		}
		if (readyTimer !== null) {
			clearTimeout(readyTimer);
			readyTimer = null;
		}
		const cleanUrl = new URL(printed.href);
		cleanUrl.searchParams.delete("token");
		log({ event: "web.ready", cleanUrl: cleanUrl.href });
		writeStatus({ ready: true, cleanUrl: cleanUrl.href, updatedAt: new Date().toISOString() });
		startProxy(token);
	});
	child.stderr.on("data", (chunk) => {
		const text = String(chunk).replace(/([?&])token=[^&\s]+/gu, "$1token=<redacted>");
		for (const line of text.split("\n")) {
			if (line.trim() !== "") log({ event: "web.stderr", line });
		}
	});
	child.on("error", (error) => {
		log({ event: "web.error", error: error.message });
	});
	child.on("exit", (code, signal) => {
		child = null;
		if (stopping) return;
		log({ event: "web.exit", code, signal, restartInMs: 3_000 });
		writeStatus({ ready: false, updatedAt: new Date().toISOString() });
		restartTimer = setTimeout(startWeb, 3_000);
	});
}

function shutdown(signal) {
	if (stopping) return;
	stopping = true;
	log({ event: "shutdown", signal });
	if (restartTimer !== null) clearTimeout(restartTimer);
	if (readyTimer !== null) clearTimeout(readyTimer);
	if (child !== null) child.kill("SIGTERM");
	const finish = () => {
		if (proxy === null) return process.exit(0);
		proxy.close().finally(() => process.exit(0));
	};
	if (child !== null) {
		child.once("exit", finish);
		setTimeout(finish, 2_000).unref();
	} else {
		finish();
	}
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
writeStatus({ ready: false, updatedAt: new Date().toISOString() });
startWeb();
