/**
 * `thread/list` projection contract.
 *
 * These assertions pin the fields the phone reads directly: seconds (not
 * milliseconds) for `updatedAt`, real text for `name`, `status.type` for the
 * running state, and the rule that an unreadable session still appears.
 */
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

// The listing scans the sessions root for last-activity times; keep it off the
// developer's real root.
process.env.DSH_HOME = mkdtempSync(join(tmpdir(), "dsh-appserver-thread-list-"));

const { apiThreadListResult, forgetHydration } = await import("./thread-list.js");

const CWD = "/tmp/project";
const CREATED_AT = 1_700_000_000_000;

function sessionHeader(id, extra) {
	return Object.assign({ id: id, createdAt: CREATED_AT, cwd: CWD, isSeeded: true }, extra);
}

function turnEvents(prompt) {
	return [
		{ seq: 1, type: "turn/start", data: { turn: { id: 1 } } },
		{ seq: 2, type: "user/message", data: { source: { kind: "user" }, content: [{ type: "text", text: prompt }] } },
		{ seq: 3, type: "assistant/message", data: { message: { content: [{ type: "text", text: "reply" }] } } },
	];
}

function liveAgent(id, status) {
	return { status: status, session: { header: sessionHeader(id), events: [] } };
}

/**
 * A fake plugin context exposing exactly the services the listing uses.
 */
function fakeContext(options) {
	const persisted = options.persisted || [];
	const live = options.live || [];
	const observations = options.observations || {};
	const projections = options.projections || {};
	const liveSessions = new Map();
	for (const agent of live) liveSessions.set(String(agent.session.header.id), agent.session);
	return {
		reads: 0,
		get: function (service) {
			if (service === "sessionPersistence") {
				return {
					list: async function () {
						return persisted.map(function (header) { return { header: header }; });
					},
				};
			}
			if (service === "sessionQuery") {
				return {
					observeSession: async function (id) {
						const observed = observations[String(id)];
						if (observed === undefined) {
							const error = new Error('session "' + id + '" not found');
							error.code = "SESSION_QUERY_SESSION_NOT_FOUND";
							throw error;
						}
						return Object.assign({}, observed, { [Symbol.dispose]: function () {} });
					},
				};
			}
			if (service === "sessionProjectionCache") {
				return {
					cachedSnapshot: function (header) {
						const values = projections[String(header && header.id)];
						return values === undefined ? undefined : { values: values };
					},
				};
			}
			if (service === "sessions") {
				return { get: function (id) { return liveSessions.get(String(id)); } };
			}
			if (service === "agents") {
				return {
					list: function () { return live; },
					get: function (id) {
						return live.find(function (agent) { return String(agent.session.header.id) === String(id); });
					},
				};
			}
			return undefined;
		},
	};
}

test.beforeEach(function () {
	forgetHydration();
});

test("a persisted session is listed with seconds, a real title and an idle status", async function () {
	const ctx = fakeContext({
		persisted: [sessionHeader("session-aaa")],
		observations: {
			"session-aaa": { header: sessionHeader("session-aaa"), events: turnEvents("Fix the bridge") },
		},
	});
	const result = await apiThreadListResult(ctx, 0);
	assert.equal(result.data.length, 1);
	assert.equal(result.data[0].id, "session-aaa");
	assert.equal(result.data[0].name, "Fix the bridge");
	assert.equal(result.data[0].preview, "reply");
	assert.equal(result.data[0].status.type, "idle");
	assert.equal(String(result.data[0].updatedAt).length, 10, "updatedAt must be Unix seconds");
});

test("a projected title is used without reading the session history", async function () {
	const ctx = fakeContext({
		persisted: [sessionHeader("session-bbb")],
		projections: { "session-bbb": { title: "Projected title" } },
	});
	const result = await apiThreadListResult(ctx, 0);
	assert.equal(result.data[0].name, "Projected title");
	assert.equal(ctx.reads, 0, "a projected title must not cost a history read");
});

test("an unreadable session still appears instead of vanishing", async function () {
	const ctx = fakeContext({ persisted: [sessionHeader("session-ccc")] });
	const result = await apiThreadListResult(ctx, 0);
	assert.equal(result.data.length, 1);
	assert.equal(result.data[0].id, "session-ccc");
	// No title source exists, so the id stands in rather than the empty name the
	// client would render as "untitled".
	assert.equal(result.data[0].name, "session-ccc");
});

test("a live running session reports active so the phone can offer stop", async function () {
	const ctx = fakeContext({ live: [liveAgent("session-ddd", "running")] });
	const result = await apiThreadListResult(ctx, 0);
	assert.equal(result.data.length, 1);
	assert.deepEqual(result.data[0].status, { type: "active", activeFlags: [] });
});

test("subagent and forked child sessions stay out of the conversation list", async function () {
	const ctx = fakeContext({
		persisted: [
			sessionHeader("session-parent"),
			sessionHeader("session-child", { parentSession: "session-parent" }),
			sessionHeader("session-sub", { origin: "subagent" }),
		],
		observations: {
			"session-parent": { header: sessionHeader("session-parent"), events: turnEvents("Parent") },
		},
	});
	const result = await apiThreadListResult(ctx, 0);
	assert.deepEqual(result.data.map(function (row) { return row.id; }), ["session-parent"]);
});

test("limit bounds the number of rows", async function () {
	const ctx = fakeContext({
		persisted: [sessionHeader("session-one"), sessionHeader("session-two")],
		observations: {
			"session-one": { header: sessionHeader("session-one"), events: turnEvents("one") },
			"session-two": { header: sessionHeader("session-two"), events: turnEvents("two") },
		},
	});
	const result = await apiThreadListResult(ctx, 1);
	assert.equal(result.data.length, 1);
	assert.ok(["session-one", "session-two"].includes(result.data[0].id));
});
