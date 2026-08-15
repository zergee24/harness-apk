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

/**
 * The human-navigable project key is lossy (separators collapse into `-`),
 * so it is used for display only; exact cwd comes from the session header.
 */
export function displayCwd(projectKey) {
	return projectKey.replace(/^--/, "").replace(/--$/, "").replace(/-/g, "/");
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
 * @returns [{id, cwd, updatedAt}] with `cwd` best-effort display value.
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
			if (!entry.isDirectory() || !entry.name.startsWith("session-")) continue;
			const logPath = findLogPath(root, projectDir.name, entry.name);
			if (logPath === null) continue;
			let updatedAt = 0;
			try {
				updatedAt = statSync(logPath).mtimeMs;
			} catch {
				continue;
			}
			sessions.push({
				id: entry.name,
				cwd: displayCwd(projectDir.name),
				updatedAt,
			});
		}
	}
	return sessions;
}

/**
 * Load a persisted session's events through the tree's sessionPersistence
 * service. Returns `{events, cwd}` or null when unknown/unreadable. The
 * fallback path (no service) returns null so callers treat the thread as
 * unknown rather than reading half a torn log.
 */
export async function loadPersistedSession(ctx, id) {
	const persistence = ctx.get("sessionPersistence");
	if (persistence === void 0 || typeof persistence.loadStored !== "function") {
		return null;
	}
	try {
		const stored = await persistence.loadStored(String(id));
		if (stored === void 0) return null;
		const events = Array.isArray(stored.events) ? stored.events : [];
		return {
			events,
			cwd: stored.meta?.cwd ?? null,
			updatedAt: stored.meta?.updatedAt ?? null,
		};
	} catch (error) {
		return { events: [], cwd: null, error: String(error?.message ?? error) };
	}
}
