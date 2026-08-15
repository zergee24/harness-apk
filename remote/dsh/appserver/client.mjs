#!/usr/bin/env node
/**
 * M4 G2 verification client: drives `dsh --profile appserver --listen
 * stdio://` through the canonical app-server surface and asserts the full
 * chain:
 *
 *   initialize -> thread/start -> turn/start (streaming) -> turn/steer
 *   -> thread/read (user text present) -> thread/turns/list -> thread/list
 *
 * Run twice to also verify persisted-session survival across processes
 * (thread/list enumeration + thread/read from the disk log in a fresh
 * process).
 *
 * Usage: node client.mjs
 * Env:  DSH_BIN overrides the dsh executable (default: `dsh`)
 *       SPIKE_TASK overrides the first task text
 */
import { spawn } from "node:child_process";
import readline from "node:readline";

const dshBin = process.env.DSH_BIN ?? "dsh";
const dshArgs = ["--profile", "appserver", "--listen", "stdio://"];

const child = spawn(dshBin, dshArgs, {
	stdio: ["pipe", "pipe", "inherit"],
	env: { ...process.env },
});

const rl = readline.createInterface({ input: child.stdout });
const pending = new Map();
const notifications = [];
let nextId = 0;

function request(method, params) {
	return new Promise((resolve, reject) => {
		const id = ++nextId;
		pending.set(id, { resolve, reject });
		child.stdin.write(JSON.stringify({ id, method, params }) + "\n");
		setTimeout(() => {
			if (pending.delete(id)) reject(new Error(`timeout waiting for ${method}`));
		}, 300_000);
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
		notifications.push(msg);
		console.log(`[notify] ${msg.method}`, JSON.stringify(msg.params ?? {}));
	}
});

child.on("exit", (code) => {
	if (code) {
		console.error(`[client] dsh exited with code ${code}`);
		process.exit(code ?? 1);
	}
});

let failures = 0;
function check(condition, label, detail) {
	if (condition) {
		console.log(`[ok] ${label}`);
	} else {
		failures++;
		console.error(`[FAIL] ${label}: ${detail}`);
	}
}

const firstTask = process.env.SPIKE_TASK ?? "用一句话回答：你好，请介绍一下你自己。";
const secondTask = "用一句话回答：你刚才在做什么？";

const info = await request("initialize", {});
check(info.serverInfo?.name === "dsh-appserver", "initialize serverInfo", JSON.stringify(info));
child.stdin.write(JSON.stringify({ method: "initialized", params: {} }) + "\n");

const started = await request("thread/start", { cwd: process.cwd() });
const threadId = started.thread.id;
check(typeof threadId === "string" && threadId.startsWith("session-"), "thread/start returns session id", threadId);

const turnOne = await request("turn/start", { threadId, input: [{ type: "text", text: firstTask }] });
check(turnOne.turn?.id !== void 0, "turn/start completes", JSON.stringify(turnOne));

const turnTwo = await request("turn/steer", {
	threadId, expectedTurnId: turnOne.turn.id, input: [{ type: "text", text: secondTask }],
});
check(turnTwo.turn?.id !== void 0, "turn/steer completes second turn", JSON.stringify(turnTwo));

const streamed = notifications.filter((n) => n.method === "item/agentMessage/delta");
check(streamed.length >= 2, "streamed agent deltas for both turns", `got ${streamed.length}`);
const completed = notifications.filter((n) => n.method === "turn/completed");
check(completed.length === 2, "two turn/completed notifications", `got ${completed.length}`);

const read = await request("thread/read", { threadId, includeTurns: true });
const turns = read.thread?.turns ?? [];
check(turns.length >= 2, "thread/read returns both turns", `got ${turns.length}`);
const userTexts = turns.flatMap((t) => t.items ?? [])
	.filter((item) => item.type === "userMessage")
	.map((item) => item.text ?? "");
check(userTexts.length >= 2 && userTexts.every((text) => text.trim() !== ""),
	"thread/read user messages carry text (user/message shape fix)", JSON.stringify(userTexts));
const agentTexts = turns.flatMap((t) => t.items ?? [])
	.filter((item) => item.type === "agentMessage")
	.map((item) => item.text ?? "");
check(agentTexts.length >= 2 && agentTexts.every((text) => text.trim() !== ""),
	"thread/read agent messages carry text", JSON.stringify(agentTexts));

const page = await request("thread/turns/list", { threadId, limit: 10, sortDirection: "desc", itemsView: "summary" });
check(Array.isArray(page.data) && page.data.length >= 2, "thread/turns/list summary view", JSON.stringify(page).slice(0, 200));

const list = await request("thread/list", { limit: 50 });
const listed = (list.data ?? []).some((thread) => thread.id === threadId);
check(listed, "thread/list includes the thread", JSON.stringify(list).slice(0, 200));

// Persistence probe: in this fresh process, a persisted thread from an
// earlier process must enumerate and load its history from the disk log.
const persisted = (list.data ?? []).find((thread) => thread.id !== threadId);
if (persisted) {
	const reloaded = await request("thread/read", { threadId: persisted.id, includeTurns: true });
	const reloadedTurns = reloaded.thread?.turns ?? [];
	const reloadedUserText = reloadedTurns.flatMap((t) => t.items ?? [])
		.filter((item) => item.type === "userMessage")
		.map((item) => item.text ?? "");
	check(reloadedTurns.length >= 1 && reloadedUserText.some((text) => text.trim() !== ""),
		"persisted thread reloads from disk with user text", JSON.stringify(reloaded).slice(0, 240));
} else {
	console.log("[skip] no persisted thread from an earlier process to probe");
}

child.stdin.end();
child.kill();
if (failures > 0) {
	console.error(`[client] ${failures} check(s) failed`);
	process.exit(1);
}
console.log("[client] spike chain ok");
