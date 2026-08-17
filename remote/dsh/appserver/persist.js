/**
 * @module dsh-appserver/persist — access to dsh's persisted session store
 * (~/.dsh/sessions or $DSH_HOME/sessions). Sessions survive bridge restarts:
 * enumeration uses the filesystem layout, event loading goes through the
 * tree's own `sessionPersistence` service (multi-frame zstd, format versions,
 * torn writes all handled by dsh itself).
 */
import { readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const LOG_NAMES = ["session.jsonl.zstd", "session.jsonl"];

/** The sessions root directory shared with other dsh profiles. */
export function sessionsRoot() {
	const home = process.env.DSH_HOME ?? join(homedir(), ".dsh");
	return join(home, "sessions");
}

function findLogPath(root, projectDir, sessionDir) {
	for (const name of LOG_NAMES) {
		const candidate = join(root, projectDir, sessionDir, name);
		try {
			if (statSync(candidate).isFile()) return candidate;
		} catch {
			// try next candidate
		}
	}
	return null;
}

/**
 * Enumerate persisted sessions across every project directory.
 * @returns [{id, updatedAt}]. Exact cwd is read from the session header;
 * the encoded project directory is intentionally never exposed. `updatedAt` is Unix seconds.
 */
export function listPersistedSessions() {
	const root = sessionsRoot();
	let projectDirs;
	try {
		projectDirs = readdirSync(root, { withFileTypes: true });
	} catch {
		return [];
	}
	const sessions = [];
	for (const projectDir of projectDirs) {
		if (!projectDir.isDirectory()) continue;
		let entries;
		try {
			entries = readdirSync(join(root, projectDir.name), { withFileTypes: true });
		} catch {
			continue;
		}
		for (const entry of entries) {
			if (!entry.isDirectory()) continue;
			const logPath = findLogPath(root, projectDir.name, entry.name);
			if (logPath === null) continue;
			let updatedAt = 0;
			try {
				updatedAt = Math.floor(statSync(logPath).mtimeMs / 1000);
			} catch {
				continue;
			}
			sessions.push({
				id: entry.name,
				updatedAt,
			});
		}
	}
	return sessions;
}

/**
 * Inspect a persisted session through the public sessionPersistence contract.
 * Returns `{events, header}` or null when unknown/unreadable. The
 * fallback path (no service) returns null so callers treat the thread as
 * unknown rather than reading half a torn log.
 */
export async function loadPersistedSession(ctx, id) {
	const persistence = ctx.get("sessionPersistence");
	if (persistence === void 0 || typeof persistence.inspect !== "function") {
		return null;
	}
	try {
		const inspection = await persistence.inspect(String(id));
		if (inspection === void 0) return null;
		const events = Array.isArray(inspection.events) ? inspection.events : [];
		return {
			events,
			header: inspection.meta ?? {},
		};
	} catch (error) {
		return { events: [], cwd: null, error: String(error?.message ?? error) };
	}
}
