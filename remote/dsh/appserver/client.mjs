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
		const timer = setTimeout(() => {
			if (pending.delete(id)) reject(new Error(`timeout waiting for ${method}`));
		}, 300_000);
		pending.set(id, { resolve, reject, timer });
		child.stdin.write(JSON.stringify({ id, method, params }) + "\n");
	});
}

function waitForTurnCompletion(turnId) {
	return new Promise((resolve, reject) => {
		const find = () => notifications.find((message) =>
			message.method === "turn/completed" && String(message.params?.turn?.id) === String(turnId));
		const existing = find();
		if (existing !== void 0) {
			resolve(existing);
			return;
		}
		const timer = setInterval(() => {
			const completed = find();
			if (completed === void 0) return;
			clearInterval(timer);
			clearTimeout(timeout);
			resolve(completed);
		}, 50);
		const timeout = setTimeout(() => {
			clearInterval(timer);
			reject(new Error(`timeout waiting for completion of ${turnId}`));
		}, 300_000);
	});
}

rl.on("line", (line) => {
	const msg = JSON.parse(line);
	if (msg.id !== void 0 && msg.id !== null) {
		const entry = pending.get(msg.id);
		if (entry) {
			pending.delete(msg.id);
			clearTimeout(entry.timer);
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
const secondTask = "只用一句话回答：第一条并发跟进。";
const thirdTask = "只用一句话回答：第二条并发跟进。";

const info = await request("initialize", {});
check(info.serverInfo?.name === "dsh-appserver", "initialize serverInfo", JSON.stringify(info));
child.stdin.write(JSON.stringify({ method: "initialized", params: {} }) + "\n");

const started = await request("thread/start", { cwd: process.cwd() });
const threadId = started.thread.id;
check(typeof threadId === "string" && threadId.startsWith("session-"), "thread/start returns session id", threadId);

const turnOne = await request("turn/start", { threadId, input: [{ type: "text", text: firstTask }] });
check(turnOne.turn?.id !== void 0 && turnOne.turn?.status === "inProgress", "turn/start returns accepted turn", JSON.stringify(turnOne));
await waitForTurnCompletion(turnOne.turn.id);

const [turnTwo, turnThree] = await Promise.all([
	request("turn/steer", {
		threadId, expectedTurnId: turnOne.turn.id, input: [{ type: "text", text: secondTask }],
	}),
	request("turn/steer", {
		threadId, expectedTurnId: turnOne.turn.id, input: [{ type: "text", text: thirdTask }],
	}),
]);
check(turnTwo.turn?.id !== void 0 && turnTwo.turn?.status === "inProgress", "first overlapping steer returns accepted turn", JSON.stringify(turnTwo));
check(turnThree.turn?.id !== void 0 && turnThree.turn?.status === "inProgress", "second overlapping steer returns accepted turn", JSON.stringify(turnThree));
check(new Set([turnOne.turn.id, turnTwo.turn.id, turnThree.turn.id]).size === 3,
	"overlapping steers bind distinct turns", JSON.stringify([turnOne.turn.id, turnTwo.turn.id, turnThree.turn.id]));
await Promise.all([waitForTurnCompletion(turnTwo.turn.id), waitForTurnCompletion(turnThree.turn.id)]);

const streamed = notifications.filter((n) => n.method === "item/agentMessage/delta");
check(streamed.length >= 3, "streamed agent deltas for all turns", `got ${streamed.length}`);
const startedNotifications = notifications.filter((n) => n.method === "turn/started");
const completed = notifications.filter((n) => n.method === "turn/completed");
for (const turnId of [turnOne.turn.id, turnTwo.turn.id, turnThree.turn.id]) {
	check(startedNotifications.filter((n) => String(n.params?.turn?.id) === String(turnId)).length === 1,
		`turn ${turnId} has one started notification`, JSON.stringify(startedNotifications));
	check(completed.filter((n) => String(n.params?.turn?.id) === String(turnId)).length === 1,
		`turn ${turnId} has one completed notification`, JSON.stringify(completed));
}

const read = await request("thread/read", { threadId, includeTurns: true });
const turns = read.thread?.turns ?? [];
check(turns.length >= 3, "thread/read returns all turns", `got ${turns.length}`);
const userTexts = turns.flatMap((t) => t.items ?? [])
	.filter((item) => item.type === "userMessage")
	.map((item) => item.text ?? "");
check(userTexts.length >= 3 && userTexts.every((text) => text.trim() !== ""),
	"thread/read user messages carry text (user/message shape fix)", JSON.stringify(userTexts));
const agentTexts = turns.flatMap((t) => t.items ?? [])
	.filter((item) => item.type === "agentMessage")
	.map((item) => item.text ?? "");
check(agentTexts.length >= 3 && agentTexts.every((text) => text.trim() !== ""),
	"thread/read agent messages carry text", JSON.stringify(agentTexts));

const page = await request("thread/turns/list", { threadId, limit: 10, sortDirection: "desc", itemsView: "summary" });
check(Array.isArray(page.data) && page.data.length >= 3, "thread/turns/list summary view", JSON.stringify(page).slice(0, 200));

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
