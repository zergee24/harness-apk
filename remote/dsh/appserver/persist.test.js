import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { listPersistedSessions, loadPersistedSession } from "./persist.js";

test("persisted loading uses the public inspect service contract", async () => {
	const inspection = {
		meta: { id: "session-cold", cwd: "/workspace" },
		events: [{ type: "turn/end", seq: 1, time: 1_700_000_000_100, data: { turn: 1 } }],
	};
	let inspectedId = null;
	const ctx = {
		get(name) {
			assert.equal(name, "sessionPersistence");
			return {
				async inspect(id) {
					inspectedId = id;
					return inspection;
				},
			};
		},
	};

	assert.deepEqual(await loadPersistedSession(ctx, "session-cold"), {
		events: inspection.events,
		header: inspection.meta,
	});
	assert.equal(inspectedId, "session-cold");
});

test("persisted enumeration includes UUID sessions and emits integer Unix seconds", () => {
	const root = mkdtempSync(join(tmpdir(), "dsh-session-list-"));
	const previous = process.env.DSH_HOME;
	try {
		process.env.DSH_HOME = root;
		const project = join(root, "sessions", "--Users-tony-Documents-harness-apk--");
		const session = join(project, "776345f5-3aa8-4b35-843e-dad47259400b");
		mkdirSync(session, { recursive: true });
		const log = join(session, "session.jsonl.zstd");
		writeFileSync(log, "fixture");
		utimesSync(log, 1_700_000_000.125, 1_700_000_000.125);

		assert.deepEqual(listPersistedSessions(), [{
			id: "776345f5-3aa8-4b35-843e-dad47259400b",
			updatedAt: 1_700_000_000,
		}]);
	} finally {
		if (previous === undefined) delete process.env.DSH_HOME;
		else process.env.DSH_HOME = previous;
		rmSync(root, { recursive: true, force: true });
	}
});
