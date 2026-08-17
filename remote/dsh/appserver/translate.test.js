import test from "node:test";
import assert from "node:assert/strict";
import {
	isVisibleSession,
	eventsFromSeq,
	eventsThroughTurn,
	projectTurns,
	sessionSummary,
	toProtocolSeconds,
	turnStatusFromReason,
	turnStatusString,
	turnTimestamps,
} from "./translate.js";

function event(seq, time, type, data) {
	return { seq, time, type, data };
}

const completedEvents = [
	event(0, 1_700_000_000_100, "turn/start", { turn: 1 }),
	event(1, 1_700_000_000_200, "user/message", {
		content: [{ type: "text", text: "修复荣耀真机远程字段回显" }],
		source: { kind: "user" },
	}),
	event(2, 1_700_000_000_300, "assistant/message", {
		turn: 1,
		step: 1,
		message: { content: [{ type: "text", text: "正在定位" }] },
	}),
	event(3, 1_700_000_004_900, "turn/end", { turn: 1, reason: { kind: "completed" } }),
];

test("toProtocolSeconds converts DSH epoch milliseconds to integer seconds", () => {
	assert.equal(toProtocolSeconds(1_700_000_004_900), 1_700_000_004);
	assert.equal(toProtocolSeconds(null), null);
});

test("event cursor includes the event written at the captured next sequence", () => {
	assert.deepEqual(eventsFromSeq(completedEvents, 0).map((item) => item.seq), [0, 1, 2, 3]);
	assert.deepEqual(eventsFromSeq(completedEvents, 2).map((item) => item.seq), [2, 3]);
});

test("turn event window stops before the next queued turn", () => {
	const queued = [
		...completedEvents,
		event(4, 1_700_000_005_000, "turn/start", { turn: 2 }),
		event(5, 1_700_000_005_100, "turn/end", { turn: 2, reason: { kind: "completed" } }),
	];
	assert.deepEqual(eventsThroughTurn(queued, 0, "1").map((item) => item.seq), [0, 1, 2, 3]);
	assert.deepEqual(eventsThroughTurn(queued, 1, "1").map((item) => item.seq), [1, 2, 3]);
	assert.deepEqual(eventsThroughTurn(queued, 2, "2").map((item) => item.seq), [4, 5]);
});

test("turn timestamps read the DSH event time field and emit protocol seconds", () => {
	assert.deepEqual(turnTimestamps(completedEvents, "1"), {
		startedAt: 1_700_000_000,
		completedAt: 1_700_000_004,
	});
});

test("turn status keeps interrupted distinct from failed", () => {
	assert.deepEqual(turnStatusFromReason({ kind: "completed" }), { type: "completed" });
	assert.deepEqual(turnStatusFromReason({ kind: "interrupted" }), { type: "interrupted" });
	assert.equal(turnStatusString({ type: "completed" }), "completed");
	assert.equal(turnStatusString({ type: "interrupted" }), "interrupted");
	assert.equal(turnStatusString({ type: "failed" }), "failed");
});

test("turn projection excludes injected user-role context from mobile history", () => {
	const injected = [
		event(0, 1_700_000_000_100, "turn/start", { turn: 1 }),
		event(1, 1_700_000_000_200, "user/message", {
			content: [{ type: "text", text: "真实用户请求" }],
			source: { kind: "user" },
		}),
		event(2, 1_700_000_000_300, "user/message", {
			content: [{ type: "text", text: "插件注入上下文" }],
			source: { kind: "hook" },
		}),
	];
	assert.deepEqual(projectTurns(injected)[0].items.map((item) => item.text), ["真实用户请求"]);
});

test("session summary uses real cwd, first human message, latest message, timestamp, and terminal status", () => {
	assert.deepEqual(sessionSummary(completedEvents, {
		cwd: "/Users/tony/Documents/项目/CRM/project",
		createdAt: 1_699_999_999_000,
	}), {
		name: "修复荣耀真机远程字段回显",
		preview: "修复荣耀真机远程字段回显",
		cwd: "/Users/tony/Documents/项目/CRM/project",
		updatedAt: 1_700_000_004,
		status: { type: "idle" },
	});
});

test("subagent sessions are hidden from the mobile list", () => {
	assert.equal(isVisibleSession({ cwd: "/workspace", origin: "subagent" }), false);
	assert.equal(isVisibleSession({ cwd: "/workspace" }), true);
});

test("session summary exposes an open turn as active", () => {
	const running = completedEvents.slice(0, 3);
	assert.deepEqual(sessionSummary(running, { cwd: "/workspace", createdAt: 1_699_999_999_000 }).status, {
		type: "active",
		activeFlags: [],
	});
	assert.equal(projectTurns(running)[0].status.type, "inProgress");
});
