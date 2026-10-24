/**
 * @module dsh-web-remote/proxy — browser-facing proxy for one local `dsh web`.
 *
 * `dsh web` authenticates a browser with a launch token embedded in the startup
 * URL and then keeps it in a signed cookie (30 days by default). Exposing that
 * URL across a tunnel would put the bearer token in the phone's address bar and
 * history, and the long cookie would outlive a lost device.
 *
 * This proxy takes the token out of the browser path:
 *
 * - It appends the launch token only while the browser has no dsh auth cookie,
 *   and retries one 401 on GET/HEAD with the token when a cookie went stale.
 * - It shortens the dsh auth cookie to one day.
 * - It mints a separate, HMAC-signed gate cookie on authenticated HTTP traffic
 *   (the relay's basic auth protects HTTP) and requires that cookie on
 *   WebSocket upgrades, because browsers do not send HTTP Basic credentials on
 *   a WebSocket handshake. The gate cookie is HttpOnly and one day old.
 *
 * The launch token must never be written to logs: every log entry carries the
 * redacted URL only.
 */
import http from "node:http";
import net from "node:net";
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

const TOKEN_PARAM = "token";
const GATE_COOKIE = "dsh-web-gate";
const GATE_VERSION = "v1";

/** Hide the launch token in any URL that may end up in a log line. */
export function redactUrl(value) {
	return String(value ?? "/").replace(
		new RegExp(`([?&])${TOKEN_PARAM}=[^&#]*`, "gu"),
		`$1${TOKEN_PARAM}=<redacted>`,
	);
}

/** Append the launch token to an origin-form request path. */
export function withToken(value, token) {
	const raw = String(value ?? "/");
	const [withoutHash, hash = ""] = raw.split("#");
	const [path = "/", query = ""] = withoutHash.split("?");
	if (new RegExp(`(?:^|&)${TOKEN_PARAM}=`, "u").test(query)) {
		return withoutHash;
	}
	const injected = `${path}?${query === "" ? "" : `${query}&`}${TOKEN_PARAM}=${encodeURIComponent(token)}`;
	return hash === "" ? injected : `${injected}#${hash}`;
}

/** Whether the browser already carries a dsh auth cookie (name is per-process). */
export function hasAuthCookie(headers) {
	return /(?:^|;\s*)dsh-auth-[^=;]+=/u.test(String(headers?.cookie ?? ""));
}

/** Replace the server's cookie lifetime with the proxy's shorter one. */
export function shortenCookie(cookie, maxAgeSeconds, now = Date.now()) {
	let shortened = String(cookie);
	if (/Max-Age=\d+/iu.test(shortened)) {
		shortened = shortened.replace(/Max-Age=\d+/iu, `Max-Age=${maxAgeSeconds}`);
	}
	if (/Expires=[^;]+/iu.test(shortened)) {
		const expires = new Date(now + maxAgeSeconds * 1000).toUTCString();
		shortened = shortened.replace(/Expires=[^;]+/iu, `Expires=${expires}`);
	}
	return shortened;
}

/** Read one cookie value from a request header. */
export function readCookie(headers, name) {
	const match = String(headers?.cookie ?? "").match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`, "u"));
	return match?.[1];
}

/** Mint the proxy's gate cookie value: v1.<issuedAtMs>.<hmac>. */
export function signGate(secret, issuedAt = Date.now()) {
	const payload = `${GATE_VERSION}.${issuedAt}`;
	const mac = createHmac("sha256", secret).update(payload).digest("base64url");
	return `${payload}.${mac}`;
}

/** Verify signature and age of a gate cookie value. */
export function verifyGate(secret, value, maxAgeSeconds, now = Date.now()) {
	const parts = String(value ?? "").split(".");
	if (parts.length !== 3 || parts[0] !== GATE_VERSION) return false;
	const issuedAt = Number(parts[1]);
	if (!Number.isFinite(issuedAt) || issuedAt > now + 300_000 || now - issuedAt > maxAgeSeconds * 1000) return false;
	const expected = createHmac("sha256", secret).update(`${GATE_VERSION}.${issuedAt}`).digest("base64url");
	const actualBuffer = Buffer.from(parts[2]);
	const expectedBuffer = Buffer.from(expected);
	return actualBuffer.length === expectedBuffer.length && timingSafeEqual(actualBuffer, expectedBuffer);
}

/**
 * Create one browser-facing proxy. One proxy serves one dsh web instance and
 * keeps its token and gate secret in memory, so a `dsh web` restart can rotate
 * the launch token without touching the browser.
 *
 * @param {object} options
 * @param {number} options.upstreamPort local `dsh web` port.
 * @param {string} options.token current `dsh web` launch token.
 * @param {string} [options.upstreamHost] local `dsh web` host.
 * @param {number} [options.cookieMaxAgeSeconds] shortened dsh-cookie lifetime.
 * @param {number} [options.gateMaxAgeSeconds] gate-cookie lifetime.
 * @param {string} [options.gateSecret] HMAC secret for gate cookies.
 * @param {(entry: object) => void} [options.log] structured log sink.
 */
export function createWebProxy(options) {
	const upstreamHost = options.upstreamHost ?? "127.0.0.1";
	const upstreamPort = options.upstreamPort;
	const cookieMaxAgeSeconds = options.cookieMaxAgeSeconds ?? 86_400;
	const gateMaxAgeSeconds = options.gateMaxAgeSeconds ?? 86_400;
	const gateSecret = options.gateSecret ?? randomBytes(32).toString("hex");
	const log = options.log ?? (() => {});
	if (!Number.isInteger(upstreamPort) || upstreamPort < 1) {
		throw new Error("upstreamPort must be a positive integer");
	}
	if (typeof options.token !== "string" || options.token === "") {
		throw new Error("token is required");
	}
	let currentToken = options.token;
	const upgradedSockets = new Set();
	const stats = {
		requests: 0,
		injected: 0,
		cookieRewrites: 0,
		upgrades: 0,
		gateCookies: 0,
		gateRejects: 0,
		errors: 0,
		reauth: 0,
		tokenRotations: 1,
	};

	function gateCookieHeader(request) {
		const secure = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim() === "https";
		return `${GATE_COOKIE}=${signGate(gateSecret)}; Max-Age=${gateMaxAgeSeconds}; Path=/; HttpOnly; SameSite=Strict${secure ? "; Secure" : ""}`;
	}

	function forwardHttp(request, response, startedAt, inject, allowRetry, gateMissing, retried = false) {
		const upstreamPath = inject ? withToken(request.url ?? "/", currentToken) : (request.url ?? "/");
		if (inject) stats.injected += 1;
		const upstream = http.request({
			host: upstreamHost,
			port: upstreamPort,
			method: request.method,
			path: upstreamPath,
			headers: { ...request.headers, host: request.headers.host ?? `${upstreamHost}:${upstreamPort}` },
		}, (upstreamResponse) => {
			const status = upstreamResponse.statusCode ?? 502;
			if (allowRetry && !inject && status === 401 && (request.method === "GET" || request.method === "HEAD")) {
				stats.reauth += 1;
				upstreamResponse.resume();
				forwardHttp(request, response, startedAt, true, false, gateMissing, true);
				return;
			}
			const headers = { ...upstreamResponse.headers };
			let cookieRewrites = 0;
			const cookies = [];
			if (Array.isArray(headers["set-cookie"])) {
				for (const cookie of headers["set-cookie"]) {
					const shortened = shortenCookie(cookie, cookieMaxAgeSeconds);
					if (shortened !== cookie) cookieRewrites += 1;
					cookies.push(shortened);
				}
			} else if (typeof headers["set-cookie"] === "string") {
				const shortened = shortenCookie(headers["set-cookie"], cookieMaxAgeSeconds);
				if (shortened !== headers["set-cookie"]) cookieRewrites += 1;
				cookies.push(shortened);
			}
			if (gateMissing) {
				cookies.push(gateCookieHeader(request));
				stats.gateCookies += 1;
			}
			if (cookies.length > 0) headers["set-cookie"] = cookies;
			stats.cookieRewrites += cookieRewrites;
			response.writeHead(status, headers);
			upstreamResponse.pipe(response);
			upstreamResponse.on("end", () => log({
				kind: "http",
				method: request.method,
				path: redactUrl(request.url),
				status,
				injected: inject,
				reauth: retried,
				gateSet: gateMissing,
				cookieRewrites,
				ms: Date.now() - startedAt,
			}));
		});
		upstream.on("error", (error) => {
			stats.errors += 1;
			if (!response.headersSent) {
				response.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
			}
			response.end("dsh web upstream unavailable\n");
			log({
				kind: "http",
				method: request.method,
				path: redactUrl(request.url),
				status: 502,
				injected: inject,
				reauth: retried,
				gateSet: gateMissing,
				error: error.code ?? error.message,
				ms: Date.now() - startedAt,
			});
		});
		if (request.readableEnded) upstream.end();
		else request.pipe(upstream);
	}

	const server = http.createServer((request, response) => {
		stats.requests += 1;
		const gateMissing = !verifyGate(gateSecret, readCookie(request.headers, GATE_COOKIE), gateMaxAgeSeconds);
		forwardHttp(request, response, Date.now(), !hasAuthCookie(request.headers), true, gateMissing);
	});

	server.on("upgrade", (request, socket, head) => {
		if (!verifyGate(gateSecret, readCookie(request.headers, GATE_COOKIE), gateMaxAgeSeconds)) {
			stats.gateRejects += 1;
			socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
			socket.destroy();
			log({ kind: "ws", path: redactUrl(request.url), rejected: "gate" });
			return;
		}
		stats.upgrades += 1;
		const inject = !hasAuthCookie(request.headers);
		if (inject) stats.injected += 1;
		const upstreamPath = inject ? withToken(request.url ?? "/", currentToken) : (request.url ?? "/");
		const upstream = net.connect(upstreamPort, upstreamHost, () => {
			const lines = [`${request.method} ${upstreamPath} HTTP/1.1`];
			for (let index = 0; index < request.rawHeaders.length; index += 2) {
				lines.push(`${request.rawHeaders[index]}: ${request.rawHeaders[index + 1]}`);
			}
			upstream.write(`${lines.join("\r\n")}\r\n\r\n`);
			if (head !== undefined && head.length > 0) upstream.write(head);
			upstream.pipe(socket);
			socket.pipe(upstream);
			upgradedSockets.add(socket);
			socket.on("close", () => upgradedSockets.delete(socket));
			log({ kind: "ws", path: redactUrl(request.url), injected: inject });
		});
		const close = () => {
			upstream.destroy();
			socket.destroy();
		};
		upstream.on("error", (error) => {
			stats.errors += 1;
			log({ kind: "ws", path: redactUrl(request.url), error: error.code ?? error.message });
			close();
		});
		upstream.on("close", () => socket.destroy());
		socket.on("error", close);
		socket.on("close", () => upstream.destroy());
	});

	server.on("error", (error) => {
		stats.errors += 1;
		log({ kind: "proxy", error: error.code ?? error.message });
	});

	return {
		server,
		stats,
		getToken() {
			return currentToken;
		},
		/** Adopt the token printed by a new `dsh web` process. */
		rotateToken(nextToken) {
			if (typeof nextToken !== "string" || nextToken === "" || nextToken === currentToken) return;
			currentToken = nextToken;
			stats.tokenRotations += 1;
		},
		listen(port, host = "127.0.0.1") {
			return new Promise((resolve, reject) => {
				server.once("error", reject);
				server.listen(port, host, () => resolve(server.address()));
			});
		},
		close() {
			for (const socket of upgradedSockets) socket.destroy();
			upgradedSockets.clear();
			server.closeAllConnections?.();
			return new Promise((resolve) => server.close(() => resolve()));
		},
	};
}
