import test from "node:test";
import assert from "node:assert/strict";
import { serializeTurnStart } from "./turn-queue.js";

test("same-thread turn starts are serialized before capturing another cursor", async () => {
	let releaseFirst;
	const firstGate = new Promise((resolve) => { releaseFirst = resolve; });
	const order = [];
	const first = serializeTurnStart("thread-1", async () => {
		order.push("first:start");
		await firstGate;
		order.push("first:end");
		return "turn-1";
	});
	const second = serializeTurnStart("thread-1", async () => {
		order.push("second:start");
		return "turn-2";
	});

	await Promise.resolve();
	await Promise.resolve();
	assert.deepEqual(order, ["first:start"]);
	releaseFirst();
	assert.equal(await first, "turn-1");
	assert.equal(await second, "turn-2");
	assert.deepEqual(order, ["first:start", "first:end", "second:start"]);
});
