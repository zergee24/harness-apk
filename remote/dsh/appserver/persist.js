/**
 * @module dsh-appserver/persist — access to dsh's persisted session store
 * through the public persistence and query services.
 *
 * Two rules shape this module:
 *
 * 1. Enumeration and reading go through `ctx.sessionPersistence.list()` and
 *    `ctx.sessionQuery.observeSession()`. Both select the numerically highest
 *    canonical log generation themselves (`session.jsonl.zstd` for the
 *    released v0, `session.vN.jsonl.zstd` afterwards), so no filename is ever
 *    hardcoded here — a filesystem walk that only knows the v0 names misses
 *    every session whose current log is a versioned generation.
 * 2. Reading history never takes write ownership. The JSONL backend holds a
 *    `session.lock` flock for each write handle, so a read-only observation is
 *    the only safe way to look at a session another process (the Desktop app)
 *    is actively using.
 */
import { readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

/** A session whose history could not be observed. */
export class SessionUnavailableError extends Error {
	/**
	 * @param message - operator-facing explanation.
	 * @param code - stable code the appserver maps onto a JSON-RPC error.
	 */
	constructor(message, code = "session_unavailable") {
		super(message);
		this.name = "SessionUnavailableError";
		this.code = code;
	}
}

/**
 * Whether an unknown error is the JSONL backend refusing a second writer —
 * either an in-process claim or the cross-process `session.lock` flock. Both
 * surface as `SessionAlreadyOwnedError`, so its message is the stable witness.
 * @param error - error thrown by a persistence or agent call.
 */
export function isWriteOwnershipConflict(error) {
	return String(error?.message ?? error ?? "").includes("is already owned by an active write handle");
}

/**
 * Whether an unknown error means the session does not exist. The query service
 * throws `SessionQueryError` with a stable code; the message check covers a
 * cross-instance class identity mismatch.
 * @param error - error thrown by a query or persistence call.
 */
export function isSessionNotFound(error) {
	if (error?.code === "SESSION_QUERY_SESSION_NOT_FOUND") return true;
	return /session "[^"]*" not found/u.test(String(error?.message ?? error ?? ""));
}

/** The sessions root directory shared with other dsh profiles. */
export function sessionsRoot() {
	const home = process.env.DSH_HOME ?? join(homedir(), ".dsh");
	return join(home, "sessions");
}

/**
 * Last-activity time in Unix seconds for every session directory, keyed by the
 * escaped path segment dsh derives from the session id.
 *
 * `sessionPersistence.list()` reports each session header but no timestamp,
 * and the wire wants seconds. One directory walk (never log contents) costs
 * far less than a per-session search across every project directory.
 * @returns Map from escaped session segment to Unix seconds.
 */
function scanLastActivity() {
	const root = sessionsRoot();
	const activity = new Map();
	let projectDirs;
	try {
		projectDirs = readdirSync(root, { withFileTypes: true });
	} catch {
		return activity;
	}
	for (const projectDir of projectDirs) {
		if (!projectDir.isDirectory()) continue;
		let sessionDirs;
		try {
			sessionDirs = readdirSync(join(root, projectDir.name), { withFileTypes: true });
		} catch {
			continue;
		}
		for (const sessionDir of sessionDirs) {
			if (!sessionDir.isDirectory()) continue;
			const directory = join(root, projectDir.name, sessionDir.name);
			let entries;
			try {
				entries = readdirSync(directory, { withFileTypes: true });
			} catch {
				continue;
			}
			let newest = null;
			for (const entry of entries) {
				// `session.lock` is a zero-byte flock target whose release never
				// removes it, so its mtime says nothing about activity.
				if (!entry.isFile() || !entry.name.startsWith("session.")) continue;
				try {
					const millis = statSync(join(directory, entry.name)).mtimeMs;
					if (newest === null || millis > newest) newest = millis;
				} catch {
					// a log removed mid-scan is not worth failing the listing
				}
			}
			if (newest !== null) {
				activity.set(sessionDir.name, Math.floor(newest / 1000));
			}
		}
	}
	return activity;
}

/**
 * dsh escapes a session id into one safe path segment (no traversal, no
 * collision): unsafe characters become `~XXXX` with the code point in
 * uppercase hex. Ids observed on this machine are plain UUIDs and
 * `session-<uuid>` strings, which need no escaping.
 * @param id - raw session identity.
 */
function escapedSegment(id) {
	return id.replace(/[^A-Za-z0-9._-]/gu, (character) =>
		`~${character.codePointAt(0).toString(16).toUpperCase().padStart(4, "0")}`);
}

/**
 * Enumerate every persisted session. `list()` translates only the selected
 * generation's header, so this stays a header-only read across the whole root.
 * @param ctx - plugin context carrying `sessionPersistence`.
 * @returns `[{header, updatedAt}]`, newest first, or [] without a backend.
 */
export async function listStoredSessions(ctx) {
	const persistence = ctx.get("sessionPersistence");
	if (persistence === void 0 || typeof persistence.list !== "function") return [];
	let snapshots;
	try {
		snapshots = await persistence.list();
	} catch {
		return [];
	}
	const sessions = [];
	const activity = scanLastActivity();
	for (const snapshot of snapshots ?? []) {
		const header = snapshot?.header;
		if (header?.id === void 0) continue;
		const id = String(header.id);
		sessions.push({
			header,
			updatedAt: activity.get(escapedSegment(id)) ?? Math.floor((header.createdAt ?? 0) / 1000),
		});
	}
	sessions.sort((left, right) => right.updatedAt - left.updatedAt);
	return sessions;
}

/**
 * Observe one session's committed events without taking write ownership.
 * @param ctx - plugin context carrying `sessionQuery`.
 * @param threadId - session identity from the wire.
 * @returns `{events, header, live}` or null when the session is unknown.
 */
export async function observeSessionEvents(ctx, threadId) {
	const query = ctx.get("sessionQuery");
	if (query === void 0 || typeof query.observeSession !== "function") {
		throw new SessionUnavailableError(
			"session history is unavailable: this appserver profile has no sessionQuery backend",
			"session_query_unavailable",
		);
	}
	let observation;
	try {
		observation = await query.observeSession(String(threadId));
	} catch (error) {
		if (isSessionNotFound(error)) return null;
		throw error;
	}
	try {
		return {
			events: observation.events ?? [],
			header: observation.header ?? {},
			live: observation.source === "live",
		};
	} finally {
		observation[Symbol.dispose]?.();
	}
}

/**
 * Projection values for one session from the persisted projection cache — the
 * zero-I/O listing read. `@deepseek-ai/dsh-session-title` registers the
 * `title` key when this composition mounts it; a composition without that
 * plugin simply has no cached title and callers fall back to event text.
 * @param ctx - plugin context carrying `sessionProjectionCache`.
 * @param header - the session header a snapshot is requested for.
 * @returns projection values, or undefined when no cached snapshot matches.
 */
export function cachedProjectionValues(ctx, header) {
	const cache = ctx.get("sessionProjectionCache");
	if (cache === void 0 || typeof cache.cachedSnapshot !== "function") return void 0;
	try {
		return cache.cachedSnapshot(header)?.values;
	} catch {
		return void 0;
	}
}
