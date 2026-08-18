import test from "node:test";
import assert from "node:assert/strict";
import { serializeTurnStart } from "./turn-queue.js";
import { apiThreadListResult } from "./thread-list.js";

test("thread list uses the zero-log ApiProxy projection baseline", async () => {
	let inspectCalls = 0;
	const ctx = {
		get(name) {
			if (name === "apiProxy") return {
				sessions: {
					async list(request) {
						assert.equal(request.payload.cursor, undefined);
						return {
							rpcId: request.rpcId,
							result: { ok: true, value: { items: [
								{
									sessionId: "session-root",
									updatedAt: 1_786_800_000_000,
									running: false,
									blank: false,
									cwd: "/work/root",
									projections: { values: {
										title: "Root title",
										sessionListMetadata: { blank: false, lastPromptAt: 1_786_800_001_000 },
									} },
								},
								{ sessionId: "session-child", updatedAt: 1_786_800_002_000, running: true, blank: false, origin: "subagent" },
								{ sessionId: "session-blank", updatedAt: 1_786_800_003_000, running: false, blank: true },
							] } },
						};
					},
				},
			};
			if (name === "sessionPersistence") return { async inspect() { inspectCalls++; throw new Error("must not inspect"); } };
		},
	};

	const result = await apiThreadListResult(ctx, 20);

	assert.equal(inspectCalls, 0);
	assert.deepEqual(result.data, [{
		id: "session-root",
		name: "Root title",
		preview: "",
		cwd: "/work/root",
		updatedAt: 1_786_800_001,
		status: { type: "idle" },
	}]);
});

test("thread list falls back to public projection services without inspecting logs", async () => {
	let inspectCalls = 0;
	const meta = { id: "session-cold", createdAt: 1_786_700_000_000, cwd: "/work/cold" };
	const ctx = {
		get(name) {
			if (name === "sessionPersistence") return {
				async list() { return [meta]; },
				async inspect() { inspectCalls++; throw new Error("must not inspect"); },
			};
			if (name === "sessionProjectionCache") return {
				cachedSnapshot(actual) {
					assert.equal(actual, meta);
					return { values: {
						title: "Cold title",
						// Cold-cache blank:true is only a checkpoint-prefix hint; without an
						// authoritative log probe the session must stay visible.
						sessionListMetadata: { blank: true, lastPromptAt: 1_786_700_005_000 },
					} };
				},
			};
			if (name === "agents") return { get() { return undefined; } };
		},
	};

	const result = await apiThreadListResult(ctx, 20);

	assert.equal(inspectCalls, 0);
	assert.deepEqual(result.data, [{
		id: "session-cold", name: "Cold title", preview: "", cwd: "/work/cold",
		updatedAt: 1_786_700_005, status: { type: "idle" },
	}]);
});

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
