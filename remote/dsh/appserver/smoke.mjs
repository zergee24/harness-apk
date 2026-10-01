#!/usr/bin/env node
/**
 * Offline smoke for the appserver's session-sharing surface.
 *
 * It drives a real `dsh --profile appserver` process over stdio and asserts the
 * contract the Mac bridge and the phone depend on, without spending a model
 * call:
 *
 *   initialize → thread/list (seconds, real titles, no v4-only blind spot)
 *   → thread/resume on a session created by this process
 *   → turn/interrupt on a thread with no live turn (deterministic error code)
 *   → thread/read + thread/turns/list on a session this process owns
 *
 * Usage:
 *   DSH_HOME=/tmp/dsh-appserver-smoke node smoke.mjs
 * Env: DSH_BIN overrides the dsh executable (default: `dsh`).
 */
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import readline from "node:readline";

const dshBin = process.env.DSH_BIN ?? "dsh";
const child = spawn(dshBin, ["--profile", "appserver", "--listen", "stdio://"], {
	stdio: ["pipe", "pipe", "inherit"],
	env: { ...process.env },
});

const pending = new Map();
let nextId = 0;
readline.createInterface({ input: child.stdout }).on("line", (line) => {
	if (line.trim() === "") return;
	let msg;
	try {
		msg = JSON.parse(line);
	} catch {
		return;
	}
	if (msg.id === void 0 || msg.id === null) return;
	const entry = pending.get(msg.id);
	if (entry === void 0) return;
	pending.delete(msg.id);
	// Reject with the structured error so a test can assert on its code.
	if (msg.error) {
		const error = Object.assign(new Error(msg.error.message), { code: msg.error.code });
		if (process.env.SMOKE_TRACE === "1" && msg.error.data !== void 0) error.trace = msg.error.data;
		entry.reject(error);
	} else {
		entry.resolve(msg.result);
	}
});

function request(method, params) {
	return new Promise((resolve, reject) => {
		const id = ++nextId;
		pending.set(id, { resolve, reject });
		child.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
		setTimeout(() => {
			if (pending.delete(id)) reject(new Error(`timeout waiting for ${method}`));
		}, 60_000);
	});
}

let failures = 0;
function check(condition, label, detail) {
	if (condition) {
		console.log(`[ok] ${label}`);
		return;
	}
	failures += 1;
	console.error(`[FAIL] ${label}${detail === void 0 ? "" : `: ${detail}`}`);
}

/** Assert that a request fails with an exact JSON-RPC error code. */
async function expectError(method, params, code, label) {
	try {
		const result = await request(method, params);
		check(false, label, `expected ${code}, got result ${JSON.stringify(result)}`);
	} catch (error) {
		check(error.code === code, label, `expected ${code}, got ${error.code}: ${error.message}`);
	}
}

const home = process.env.DSH_HOME ?? mkdtempSync(join(tmpdir(), "dsh-appserver-smoke-"));
console.log(`[info] DSH_HOME=${home}`);

const info = await request("initialize", {});
check(info.serverInfo?.name === "dsh-appserver", "initialize", JSON.stringify(info));
child.stdin.write(`${JSON.stringify({ method: "initialized", params: {} })}\n`);

// A session created here proves the listing, resume and read paths on a log
// this process owns; a session from the Desktop app goes through the same code.
const cwd = mkdtempSync(join(tmpdir(), "dsh-appserver-smoke-project-"));
const started = await request("thread/start", { cwd });
const threadId = started.thread?.id;
check(typeof threadId === "string" && threadId.length > 0, "thread/start returns an id", String(threadId));
check(
	typeof started.thread?.updatedAt === "number" && String(started.thread.updatedAt).length === 10,
	"thread/start updatedAt is Unix seconds",
	JSON.stringify(started.thread),
);

const listed = await request("thread/list", { limit: 0 });
const rows = listed.data ?? [];
check(Array.isArray(rows), "thread/list returns data", JSON.stringify(listed).slice(0, 200));
const row = rows.find((candidate) => candidate.id === threadId);
check(row !== void 0, "thread/list contains the new session", `ids=${rows.map((item) => item.id).join(",")}`);
if (row !== void 0) {
	check(
		typeof row.updatedAt === "number" && String(row.updatedAt).length === 10,
		"thread/list updatedAt is Unix seconds",
		String(row.updatedAt),
	);
	check(typeof row.name === "string" && row.name.length > 0, "thread/list name is real text", JSON.stringify(row.name));
	check(row.status?.type === "idle" || row.status?.type === "active", "thread/list status type", JSON.stringify(row.status));
}

const resumed = await request("thread/resume", { threadId });
check(resumed.thread?.id === threadId, "thread/resume returns the same thread", JSON.stringify(resumed).slice(0, 200));

const read = await request("thread/read", { threadId, includeTurns: true });
check(read.thread?.id === threadId && Array.isArray(read.thread?.turns), "thread/read returns turns", JSON.stringify(read).slice(0, 200));

const turns = await request("thread/turns/list", { threadId, limit: 3, sortDirection: "desc" });
check(Array.isArray(turns.data), "thread/turns/list returns data", JSON.stringify(turns).slice(0, 200));

// Interrupting a session this backend owns is a no-op cancel: dsh acknowledges
// the signal (`accepted`) rather than failing, matching the Desktop stop
// button. Interrupting a session it does NOT own must fail deterministically,
// because reporting a stop that cannot happen is worse than an error the
// bridge can classify.
const interrupted = await request("turn/interrupt", { threadId });
check(interrupted.accepted === true, "turn/interrupt acks a session this backend owns", JSON.stringify(interrupted));
await expectError(
	"turn/interrupt",
	{ threadId: "session-does-not-exist" },
	"thread_not_found",
	"turn/interrupt reports a session this backend cannot stop",
);

// A missing session must be distinguishable from a locked or failed one.
await expectError("thread/resume", { threadId: "session-does-not-exist" }, "thread_not_found", "thread/resume on an unknown session");

child.stdin.end();
child.kill("SIGTERM");
console.log(failures === 0 ? "[done] smoke passed" : `[done] ${failures} check(s) failed`);
process.exit(failures === 0 ? 0 : 1);
