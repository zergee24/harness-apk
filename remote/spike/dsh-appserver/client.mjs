#!/usr/bin/env node
/**
 * M4 G0 spike client: drives `dsh --profile appserver --listen stdio://`
 * through the canonical app-server JSON-RPC surface and prints every
 * response and notification.
 *
 * Usage: node client.mjs [dsh-args...]
 * Env:  DSH_BIN overrides the dsh executable (default: `dsh`).
 */
import { spawn } from "node:child_process";
import readline from "node:readline";

const dshBin = process.env.DSH_BIN ?? "dsh";
const dshArgs = process.argv.slice(2).length > 0
	? process.argv.slice(2)
	: ["--profile", "appserver", "--listen", "stdio://"];

const child = spawn(dshBin, dshArgs, {
	stdio: ["pipe", "pipe", "inherit"],
	env: { ...process.env },
});

const rl = readline.createInterface({ input: child.stdout });
const pending = new Map();
let nextId = 0;

function request(method, params) {
	return new Promise((resolve, reject) => {
		const id = ++nextId;
		pending.set(id, { resolve, reject });
		child.stdin.write(JSON.stringify({ id, method, params }) + "\n");
		setTimeout(() => {
			if (pending.delete(id)) reject(new Error(`timeout waiting for ${method}`));
		}, 180_000);
	});
}

rl.on("line", (line) => {
	const msg = JSON.parse(line);
	if (msg.id !== void 0 && msg.id !== null) {
		const entry = pending.get(msg.id);
		if (entry) {
			pending.delete(msg.id);
			if (msg.error) entry.reject(new Error(`${msg.error.code}: ${msg.error.message}`));
			else entry.resolve(msg.result);
		}
	} else {
		console.log(`[notify] ${msg.method}`, JSON.stringify(msg.params ?? {}));
	}
});

child.on("exit", (code) => {
	console.error(`[client] dsh exited with code ${code}`);
	process.exit(code ?? 1);
});

const task = process.env.SPIKE_TASK ?? "用一句话回答：你好，请介绍一下你自己。";
console.log("[client] initialize");
const info = await request("initialize", {});
console.log("[client] initialize ->", JSON.stringify(info));
child.stdin.write(JSON.stringify({ method: "initialized", params: {} }) + "\n");

console.log("[client] thread/start");
const started = await request("thread/start", { cwd: process.cwd() });
const threadId = started.thread.id;
console.log("[client] thread/start ->", JSON.stringify(started));

console.log(`[client] turn/start: ${task}`);
const turn = await request("turn/start", {
	threadId,
	input: [{ type: "text", text: task }],
});
console.log("[client] turn/start ->", JSON.stringify(turn));

console.log("[client] thread/read");
const read = await request("thread/read", { threadId, includeTurns: true });
console.log("[client] thread/read turns:", JSON.stringify(read.thread?.turns?.length ?? 0));
for (const t of read.thread?.turns ?? []) {
	for (const item of t.items ?? []) {
		console.log(`  [${item.type}] ${String(item.text ?? "").slice(0, 200)}`);
	}
}

console.log("[client] thread/list");
const list = await request("thread/list", {});
console.log("[client] thread/list ->", JSON.stringify(list));

child.stdin.end();
child.kill();
console.log("[client] spike ok");
