/**
 * @module dsh-appserver/thread-list — dsh sessions → codex `thread/list` rows.
 *
 * The mobile surface reads these fields directly:
 *
 * - `name` / `preview` become the conversation card title and summary. They
 *   must be real text: the Android client renders "未命名线程" for an empty
 *   name, so a path or session id here is a visible defect.
 * - `updatedAt` is Unix **seconds**; the client multiplies by 1000. Reporting
 *   milliseconds moves every timestamp a thousand times into the future.
 * - `status.type === "active"` with no active flags maps to RUNNING, which is
 *   what enables the phone's stop button for a live turn.
 *
 * Titles come from the persisted projection cache when the composition mounts
 * `@deepseek-ai/dsh-session-title`; only sessions without a cached title pay
 * for a history read, and each one pays once per revision.
 */
import { cachedProjectionValues, listStoredSessions, observeSessionEvents } from "./persist.js";
import { textBlocks } from "./translate.js";

const TITLE_MAX = 60;
const PREVIEW_MAX = 240;

/**
 * Rows hydrated (history read) when a caller passes no limit. A listing must
 * stay fast on a real machine — this one holds ~1800 sessions and ~840 MB of
 * logs — so an unbounded request is bounded here, and titles fill in over
 * later polls instead of blocking the first one.
 */
const HYDRATE_ROWS_MAX = 50;

/**
 * Total time budget for hydrating one listing. Hydration is best-effort: a
 * session whose read loses the race keeps its id as the title until the next
 * poll, which is strictly better than making the phone wait on a 38 MB log.
 */
const HYDRATE_BUDGET_MS = 3000;

/** Per-session read bound, so one slow log cannot consume the whole budget. */
const HYDRATE_OBSERVE_MS = 1200;

/** Milliseconds → Unix seconds, as the mobile wire requires. */
export function protocolSeconds(value) {
	return typeof value === "number" && Number.isFinite(value) ? Math.floor(value / 1000) : 0;
}

/** Collapse whitespace and bound the length for a card field. */
export function cardText(value, maximum) {
	const collapsed = String(value ?? "").replace(/\s+/gu, " ").trim();
	return collapsed.length <= maximum ? collapsed : `${collapsed.slice(0, maximum - 1)}…`;
}

/** First user text in a session, used when no cached title exists. */
function firstUserText(events) {
	for (const event of events) {
		if (event?.type !== "user/message") continue;
		const text = textBlocks(event.data).trim();
		if (text !== "") return text;
	}
	return "";
}

/** Latest assistant text in a session, used as the card preview. */
function lastAssistantText(events) {
	for (let index = events.length - 1; index >= 0; index -= 1) {
		const event = events[index];
		if (event?.type !== "assistant/message") continue;
		const text = textBlocks(event.data?.message).trim();
		if (text !== "") return text;
	}
	return "";
}

/**
 * Observations keyed by `id@updatedAt`: a card is hydrated at most once per
 * revision, so a phone refresh loop never re-decodes unchanged session logs.
 */
const hydration = new Map();

/** Drop cached observations so a later listing re-reads the session. */
export function forgetHydration(threadId) {
	if (threadId === void 0) hydration.clear();
	else hydration.delete(String(threadId));
}

/**
 * Whether a session is a conversation the phone should open. Subagent sessions
 * and forked children belong to their parent's history, not to the list.
 * @param header - session header from a listing or a live agent.
 */
function isListable(header) {
	return header?.origin !== "subagent" && header?.parentSession === void 0;
}

/** Running state as the mobile client's execution projection reads it. */
function statusOf(running) {
	return running === true ? { type: "active", activeFlags: [] } : { type: "idle" };
}

/**
 * Fill in title and preview for one row. A cached title is authoritative, so
 * the read is skipped entirely in that case; otherwise the committed events
 * provide the first user prompt and the latest assistant reply.
 * @param ctx - plugin context carrying `sessionQuery`.
 * @param row - the row built from header-level facts.
 * @param cacheKey - identity of the revision this row was built from.
 */
async function hydrateRow(ctx, row, cacheKey) {
	const observed = await observeSessionEvents(ctx, row.id);
	if (observed === null) return row;
	const title = cardText(
		firstUserText(observed.events) || cachedProjectionValues(ctx, observed.header)?.title,
		TITLE_MAX,
	);
	const hydrated = {
		...row,
		name: title === "" ? row.name : title,
		preview: cardText(lastAssistantText(observed.events), PREVIEW_MAX),
		cwd: observed.header.cwd ?? row.cwd,
	};
	hydration.set(cacheKey, hydrated);
	return hydrated;
}

/**
 * Build the whole `thread/list` response.
 *
 * Sessions live in this appserver process are listed from memory. Every other
 * persisted session is listed from its header — a header-only read across the
 * root — and keeps its cached or projected title. Only a session with neither
 * pays for a history read, and a session whose history cannot be read still
 * appears: a missing title must never hide a conversation.
 * @param ctx - plugin context.
 * @param limit - maximum rows (0 or negative means no limit).
 */
export async function apiThreadListResult(ctx, limit) {
	const agents = ctx.get("agents");
	const sessions = ctx.get("sessions");
	const rows = new Map();
	/** Rows whose title came from the projection cache: complete without a read. */
	const projected = new Set();
	for (const agent of agents?.list?.() ?? []) {
		const header = agent.session.header;
		if (!isListable(header)) continue;
		const id = String(header.id);
		const title = cardText(cachedProjectionValues(ctx, header)?.title, TITLE_MAX);
		if (title !== "") projected.add(id);
		rows.set(id, {
			id,
			name: title === "" ? id : title,
			preview: "",
			cwd: header.cwd ?? "",
			updatedAt: protocolSeconds(Date.now()),
			status: statusOf(agent.status === "running"),
		});
	}
	for (const entry of await listStoredSessions(ctx)) {
		const header = entry.header;
		const id = String(header.id);
		// A session published in this process is authoritative, and its live row
		// already knows the true running state.
		if (rows.has(id) || !isListable(header) || sessions?.get?.(id) !== void 0) continue;
		const title = cardText(cachedProjectionValues(ctx, header)?.title, TITLE_MAX);
		if (title !== "") projected.add(id);
		rows.set(id, {
			id,
			name: title === "" ? id : title,
			preview: "",
			cwd: header.cwd ?? "",
			updatedAt: entry.updatedAt > 0 ? entry.updatedAt : 0,
			status: statusOf(agents?.get?.(id)?.status === "running"),
		});
	}
	// A live session is never dropped by the cap: it is the one the phone is
	// most likely acting on, and its row is already complete without a read.
	const live = [];
	const stored = [];
	for (const row of rows.values()) (row.status.type === "active" ? live : stored).push(row);
	live.sort((left, right) => right.updatedAt - left.updatedAt);
	stored.sort((left, right) => right.updatedAt - left.updatedAt);
	const wanted = limit > 0 ? limit : HYDRATE_ROWS_MAX;
	const bounded = [...live, ...stored].slice(0, Math.max(wanted, live.length));
	const data = [];
	let hydrated = 0;
	const deadline = Date.now() + HYDRATE_BUDGET_MS;
	for (const row of bounded) {
		const cacheKey = `${row.id}@${row.updatedAt}`;
		const cached = hydration.get(cacheKey);
		if (cached !== void 0 && cached.status === row.status) {
			data.push(cached);
			continue;
		}
		if (projected.has(row.id)) {
			data.push(row);
			continue;
		}
		// Hydration is best-effort and time-bounded: it must never exceed the
		// rows the caller asked for, and a row that misses the budget keeps its
		// header-level name until a later poll retries it.
		if (hydrated >= Math.min(wanted, HYDRATE_ROWS_MAX) || Date.now() >= deadline) {
			data.push(row);
			continue;
		}
		hydrated += 1;
		try {
			data.push(await hydrateRow(ctx, row, cacheKey));
		} catch {
			data.push(row);
		}
	}
	return { data, now: protocolSeconds(Date.now()) };
}
