#!/usr/bin/env node
/**
 * Self-test for the DSH web proxy and runner.
 *
 * Part 1 uses a deterministic dummy upstream so every branch is observable:
 * token injection, cookie shortening, redirect, asset, WebSocket upgrade,
 * upstream failure, and token rotation.
 *
 * Part 2 drives the real `dsh web` 0.2.1-rc.3 through the runner and asserts
 * the exact contract the phone relies on: token-free URL, 303 -> cookie ->
 * 200 page, and a real asset fetch.
 *
 * Usage:
 *   node selftest.mjs
 *   DSH_BIN=~/.dsh/cli-web/node_modules/.bin/dsh node selftest.mjs
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync } from "node:fs";
import http from "node:http";
import net from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createWebProxy, redactUrl, shortenCookie, withToken } from "./proxy.mjs";

const DUMMY_TOKEN = "dummy-launch-token-0123456789";
const ROTATED_TOKEN = "rotated-launch-token-9876543210";
const results = [];

function check(name, condition, detail = "") {
	results.push({ name, ok: Boolean(condition), detail });
	if (!condition) console.error(`[FAIL] ${name}${detail === "" ? "" : `: ${detail}`}`);
	else console.log(`[ok] ${name}`);
}

function request(port, path, options = {}) {
	return new Promise((resolve, reject) => {
		const request = http.request({
			host: "127.0.0.1", port, path, method: options.method ?? "GET",
			headers: options.headers ?? {},
		}, (response) => {
			const chunks = [];
			response.on("data", (chunk) => chunks.push(chunk));
			response.on("end", () => resolve({
				status: response.statusCode,
				headers: response.headers,
				body: Buffer.concat(chunks).toString("utf8"),
			}));
		});
		request.on("error", reject);
		request.end();
	});
}

function upgradeHandshake(port, path, cookie = "") {
	return new Promise((resolve, reject) => {
		const socket = net.connect(port, "127.0.0.1", () => {
			const key = createHash("sha1").update(String(Date.now())).digest("base64");
			const lines = [
				`GET ${path} HTTP/1.1`,
				"Host: 127.0.0.1",
				"Upgrade: websocket",
				"Connection: Upgrade",
				`Sec-WebSocket-Key: ${key}`,
				"Sec-WebSocket-Version: 13",
			];
			if (cookie !== "") lines.push(`Cookie: ${cookie}`);
			lines.push("", "");
			socket.write(lines.join("\r\n"));
		});
		let data = "";
		const timer = setTimeout(() => {
			socket.destroy();
			reject(new Error("websocket handshake timeout"));
		}, 5_000);
		socket.on("data", (chunk) => {
			data += chunk.toString("utf8");
			if (data.includes("\r\n\r\n")) {
				clearTimeout(timer);
				socket.destroy();
				resolve(data.split("\r\n")[0]);
			}
		});
		socket.on("error", (error) => {
			clearTimeout(timer);
			reject(error);
		});
	});
}

function sleep(milliseconds) {
	return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitFor(description, predicate, timeoutMs = 90_000) {
	const deadline = Date.now() + timeoutMs;
	for (;;) {
		const value = await predicate();
		if (value) return value;
		if (Date.now() >= deadline) throw new Error(`timeout waiting for ${description}`);
		await sleep(250);
	}
}

function dummyUpstream(state) {
	const server = http.createServer((incoming, response) => {
		if (state.down) {
			incoming.socket.destroy();
			return;
		}
		const url = new URL(incoming.url ?? "/", "http://127.0.0.1");
		const token = url.searchParams.get("token");
		if (token !== null && token !== state.token) {
			response.writeHead(401, { "content-type": "text/plain" });
			response.end("unauthorized");
			return;
		}
		// dsh web redirects whenever a launch token is present, even with a
		// valid cookie. Mimic that so the proxy cannot inject it on every hop.
		if (token !== null) {
			response.writeHead(303, {
				location: "./",
				"set-cookie": [
					"dsh-auth-test=valid; Max-Age=2592000; Expires=Mon, 23 Nov 2026 13:13:54 GMT; Path=/; HttpOnly; SameSite=Strict",
				],
			});
			response.end();
			return;
		}
		const cookie = String(incoming.headers.cookie ?? "");
		const hasValidCookie = cookie.includes("dsh-auth-test=valid") && state.invalidateCookie !== true;
		if (!hasValidCookie) {
			response.writeHead(401, { "content-type": "text/plain" });
			response.end("unauthorized");
			return;
		}
		if (url.pathname === "/") {
			response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
			response.end("<!doctype html><title>DeepSeek Harness</title><script src=\"./asset.js\"></script>");
			return;
		}
		if (url.pathname === "/asset.js") {
			response.writeHead(200, { "content-type": "text/javascript" });
			response.end("console.log('asset');");
			return;
		}
		response.writeHead(404);
		response.end("not found");
	});
	server.on("upgrade", (incoming, socket) => {
		state.upgrades += 1;
		state.lastUpgradeUrl = incoming.url ?? "";
		state.upgradeSockets ??= new Set();
		state.upgradeSockets.add(socket);
		socket.on("close", () => state.upgradeSockets.delete(socket));
		socket.write([
			"HTTP/1.1 101 Switching Protocols",
			"Upgrade: websocket",
			"Connection: Upgrade",
			"Sec-WebSocket-Accept: test",
			"",
			"",
		].join("\r\n"));
		socket.on("error", () => {});
	});
	return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port })));
}

async function closeServer(server) {
	server.closeAllConnections?.();
	await new Promise((resolve) => server.close(() => resolve()));
}

async function partOne() {
	const state = { token: DUMMY_TOKEN, down: false, invalidateCookie: false, upgrades: 0, lastUpgradeUrl: "" };
	const { server: upstream, port: upstreamPort } = await dummyUpstream(state);
	const proxyLogs = [];
	const proxy = createWebProxy({
		upstreamPort,
		token: DUMMY_TOKEN,
		cookieMaxAgeSeconds: 86_400,
		log: (entry) => proxyLogs.push(entry),
	});
	const address = await proxy.listen(0);
	const proxyPort = address.port;

	// 1. Token-free browser request -> redirect + shortened cookie.
	const first = await request(proxyPort, "/?from=phone");
	check("redirect status is 303", first.status === 303, String(first.status));
	const cookies = Array.isArray(first.headers["set-cookie"]) ? first.headers["set-cookie"] : [];
	const cookie = cookies.find((value) => value.startsWith("dsh-auth-test=")) ?? "";
	const gateCookie = cookies.find((value) => value.startsWith("dsh-web-gate=")) ?? "";
	check("auth cookie is set", cookie !== "", JSON.stringify(first.headers));
	check("gate cookie is set on the first authenticated page", gateCookie !== "", JSON.stringify(first.headers));
	check("cookie Max-Age shortened to one day", cookie.includes("Max-Age=86400"), cookie);
	const expiresMatch = cookie.match(/Expires=([^;]+)/u);
	const expiresAt = expiresMatch === null ? Number.NaN : Date.parse(expiresMatch[1]);
	check(
		"cookie Expires shortened to about one day",
		Number.isFinite(expiresAt) && Math.abs(expiresAt - (Date.now() + 86_400_000)) < 120_000,
		String(expiresAt),
	);
	check("redirect points at ./", first.headers.location === "./", String(first.headers.location));
	check("browser response contains no launch token", !first.body.includes(DUMMY_TOKEN), first.body.slice(0, 120));

	// 2. Browser follows the redirect with its cookie -> real page.
	const second = await request(proxyPort, "/", { headers: { cookie: "dsh-auth-test=valid" } });
	check("page status is 200", second.status === 200, String(second.status));
	check("page is the DSH harness", second.body.includes("DeepSeek Harness"));
	const assetPath = second.body.match(/src="(\.\/[^"]+)"/u)?.[1]?.replace(/^\.\//u, "/") ?? "/asset.js";
	const asset = await request(proxyPort, assetPath, { headers: { cookie: "dsh-auth-test=valid" } });
	check("asset fetch is 200", asset.status === 200, String(asset.status));

	// 3. Injection/Secure handoff counters.
	check("only the tokenless browser request was injected", proxy.stats.injected === 1 && proxy.stats.requests === 3, JSON.stringify(proxy.stats));
	check("exactly one cookie was shortened", proxy.stats.cookieRewrites === 1, JSON.stringify(proxy.stats));

	// 4. WebSocket: no gate cookie is rejected; the gate cookie admits it and
	// the proxy injects the dsh launch token upstream.
	const rejected = await upgradeHandshake(proxyPort, "/ws?client=phone");
	check("websocket without gate is 401", rejected.includes("401"), rejected);
	check("gate rejection counted", proxy.stats.gateRejects === 1, JSON.stringify(proxy.stats));
	const upgradeStatus = await upgradeHandshake(proxyPort, "/ws?client=phone", gateCookie.split(";")[0]);
	check("websocket with gate is 101", upgradeStatus.includes("101"), upgradeStatus);
	check("upstream saw the upgrade", state.upgrades === 1, String(state.upgrades));
	check("upstream upgrade carried the token", state.lastUpgradeUrl.includes(`token=${DUMMY_TOKEN}`), state.lastUpgradeUrl);
	check("websocket counted once", proxy.stats.upgrades === 1, JSON.stringify(proxy.stats));

	// 5. Upstream failure is a 502 and leaks no token.
	state.down = true;
	const failed = await request(proxyPort, "/?x=1");
	state.down = false;
	check("upstream failure is 502", failed.status === 502, String(failed.status));
	check("failure body is token-free", !failed.body.includes(DUMMY_TOKEN));
	check("error was counted", proxy.stats.errors >= 1, JSON.stringify(proxy.stats));

	// 6. Token rotation is adopted without restarting the browser-facing port.
	state.token = ROTATED_TOKEN;
	proxy.rotateToken(ROTATED_TOKEN);
	const rotated = await request(proxyPort, "/");
	check("rotated token accepted", rotated.status === 303, String(rotated.status));
	check("rotation counted", proxy.stats.tokenRotations === 2, JSON.stringify(proxy.stats));

	// 7. A stale cookie gets one token retry, so a dsh web restart recovers
	// without showing the phone a 401.
	state.invalidateCookie = true;
	const reauthed = await request(proxyPort, "/?after=restart", { headers: { cookie: "dsh-auth-test=valid" } });
	state.invalidateCookie = false;
	check("stale cookie is transparently re-authenticated", reauthed.status === 303, String(reauthed.status));
	check("retry is counted", proxy.stats.reauth === 1, JSON.stringify(proxy.stats));
	check("retry injected the token", proxy.stats.injected === 5, JSON.stringify(proxy.stats));
	check("three cookies were shortened", proxy.stats.cookieRewrites === 3, JSON.stringify(proxy.stats));

	// 8. Logs never carry the token.
	const leaked = proxyLogs.filter((entry) => JSON.stringify(entry).includes(DUMMY_TOKEN) || JSON.stringify(entry).includes(ROTATED_TOKEN));
	check("proxy logs are token-free", leaked.length === 0, JSON.stringify(leaked));
	check("log paths are redacted", proxyLogs.every((entry) => entry.path === undefined || !entry.path.includes("token=") || entry.path.includes("token=<redacted>")));
	check("redactUrl hides query tokens", redactUrl("/?token=secret&x=1") === "/?token=<redacted>&x=1", redactUrl("/?token=secret&x=1"));
	check("withToken keeps an existing token", withToken("/?token=keep", "new") === "/?token=keep");
	check("shortenCookie changes Max-Age", shortenCookie("a=b; Max-Age=99; Path=/", 12).includes("Max-Age=12"));

	// Best-effort cleanup; a keep-alive peer must not stall the suite.
	for (const socket of state.upgradeSockets ?? []) socket.destroy();
	proxy.close().catch(() => {});
	upstream.closeAllConnections?.();
	upstream.close(() => {});
}

async function partTwo() {
	const settings = {
		webPort: 58_242,
		proxyPort: 58_243,
		publicUrl: "http://127.0.0.1:58243/",
		trustedHost: "127.0.0.1:58243",
	};
	const logDir = mkdtempSync(join(tmpdir(), "dsh-web-remote-selftest-"));
	const runner = spawn(process.execPath, [new URL("./dsh-web-remote.mjs", import.meta.url).pathname], {
		env: {
			...process.env,
			DSH_WEB_PORT: String(settings.webPort),
			DSH_PROXY_PORT: String(settings.proxyPort),
			DSH_PUBLIC_URL: settings.publicUrl,
			DSH_TRUSTED_HOST: settings.trustedHost,
			DSH_COOKIE_MAX_AGE: "86400",
			DSH_LOG_DIR: logDir,
		},
		stdio: ["ignore", "pipe", "pipe"],
	});
	let runnerOutput = "";
	runner.stdout.on("data", (chunk) => { runnerOutput += chunk.toString(); });
	runner.stderr.on("data", (chunk) => { runnerOutput += chunk.toString(); });
	try {
		const status = await waitFor("real dsh web readiness", () => {
			try {
				const parsed = JSON.parse(readFileSync(join(logDir, "status.json"), "utf8"));
				return parsed.ready === true ? parsed : null;
			} catch {
				return null;
			}
		}, 90_000);
		check("runner published a token-free clean URL", typeof status.cleanUrl === "string" && !status.cleanUrl.includes("token="), status.cleanUrl);

		const first = await request(settings.proxyPort, "/");
		check("real dsh web redirects on first request", first.status === 303, String(first.status));
		const cookies = Array.isArray(first.headers["set-cookie"]) ? first.headers["set-cookie"] : [];
		const cookie = cookies.find((value) => value.startsWith("dsh-auth-")) ?? "";
		const gateCookie = cookies.find((value) => value.startsWith("dsh-web-gate=")) ?? "";
		check("real dsh web set an auth cookie", cookie !== "", JSON.stringify(first.headers));
		check("real auth cookie is shortened to one day", cookie.includes("Max-Age=86400"), cookie);
		check("real gate cookie is set", gateCookie !== "", JSON.stringify(first.headers));
		const browserCookie = [cookie.split(";")[0], gateCookie.split(";")[0]].filter(Boolean).join("; ");
		const second = await request(settings.proxyPort, "/", { headers: { cookie: browserCookie } });
		check("real page is 200 with the cookie", second.status === 200, String(second.status));
		check("real page is DeepSeek Harness", second.body.includes("<title>DeepSeek Harness</title>"), second.body.slice(0, 120));
		const assetPath = second.body.match(/src="(\.\/[^"]+)"/u)?.[1]?.replace(/^\.\//u, "/") ?? "/assets/index.js";
		const asset = await request(settings.proxyPort, assetPath, { headers: { cookie: browserCookie } });
		check("real asset fetch is 200", asset.status === 200, `${assetPath} -> ${asset.status}`);

		// The token must never reach the browser or the logs.
		const browserPayloads = [first.headers, second.body, asset.body].map((value) => JSON.stringify(value)).join("\n");
		const tokenInBrowser = /token=[A-Za-z0-9_-]{40,}/u.test(browserPayloads);
		check("browser payloads contain no launch token", !tokenInBrowser);
		const runnerLog = readFileSync(join(logDir, "runner.log"), "utf8");
		const proxyLog = readFileSync(join(logDir, "proxy.log"), "utf8");
		const tokenPattern = /[?&]token=[A-Za-z0-9_-]{20,}/u;
		check("runner log is token-free", !tokenPattern.test(runnerLog), runnerLog.slice(0, 300));
		check("proxy log is token-free", !tokenPattern.test(proxyLog), proxyLog.slice(0, 300));
	} finally {
		runner.kill("SIGTERM");
		await sleep(2_500);
		if (runner.exitCode === null) runner.kill("SIGKILL");
	}
}

try {
	await partOne();
	await partTwo();
} catch (error) {
	console.error(error);
	check("selftest completed without exceptions", false, error.message);
}

const failed = results.filter((result) => !result.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
if (failed.length > 0) {
	console.log(failed.map((result) => `- ${result.name}: ${result.detail}`).join("\n"));
	process.exit(1);
}
process.exit(0);
