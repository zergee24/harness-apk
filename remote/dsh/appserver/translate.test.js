import assert from "node:assert/strict";
import test from "node:test";
import { cardText, protocolSeconds } from "./thread-list.js";
import { projectTurns, turnStatusFromReason, turnStatusString } from "./translate.js";

test("protocolSeconds converts milliseconds to the seconds the mobile wire expects", () => {
	assert.equal(protocolSeconds(1_700_000_000_000), 1_700_000_000);
	assert.equal(protocolSeconds(999), 0);
	assert.equal(protocolSeconds(Number.NaN), 0);
	assert.equal(protocolSeconds(null), 0);
	// The phone multiplies by 1000; a seconds value must stay a 10-digit number.
	assert.ok(String(protocolSeconds(Date.now())).length === 10);
});

test("cardText collapses whitespace and bounds the field", () => {
	assert.equal(cardText("  hello\n\n  world  ", 60), "hello world");
	assert.equal(cardText("", 10), "");
	assert.equal(cardText(null, 10), "");
	assert.equal(cardText("abcdefghij", 5), "abcd…");
});

test("turn status vocabulary matches what the mobile client compares", () => {
	assert.equal(turnStatusString({ kind: "completed" }), "completed");
	assert.equal(turnStatusString({ kind: "interrupted" }), "interrupted");
	assert.equal(turnStatusString({ kind: "cancelled" }), "interrupted");
	assert.equal(turnStatusString({ kind: "aborted" }), "interrupted");
	assert.equal(turnStatusString({ kind: "boom" }), "failed");
	assert.equal(turnStatusString(void 0), "completed");
	assert.deepEqual(turnStatusFromReason({ kind: "interrupted" }), { type: "interrupted" });
});

test("projectTurns reports an interrupted turn as interrupted, not failed", () => {
	const events = [
		{ seq: 1, type: "turn/start", data: { turn: { id: 7 } }, timestamp: 1_700_000_000_000 },
		{ seq: 2, type: "user/message", data: { content: [{ type: "text", text: "hi" }] } },
		{ seq: 3, type: "assistant/message", data: { message: { content: [{ type: "text", text: "partial" }] } } },
		{ seq: 4, type: "turn/end", data: { reason: { kind: "interrupted" } }, timestamp: 1_700_000_005_000 },
	];
	const turns = projectTurns(events);
	assert.equal(turns.length, 1);
	assert.equal(turns[0].id, "7");
	assert.equal(turns[0].status, "interrupted");
	assert.deepEqual(turns[0].items.map((item) => item.type), ["userMessage", "agentMessage"]);
});

test("projectTurns leaves an open turn as inProgress", () => {
	const turns = projectTurns([
		{ seq: 1, type: "turn/start", data: { turn: { id: 1 } } },
		{ seq: 2, type: "user/message", data: { content: [{ type: "text", text: "hi" }] } },
	]);
	assert.equal(turns[0].status, "inProgress");
});
