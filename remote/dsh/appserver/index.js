import { randomUUID } from "node:crypto";
import readline from "node:readline";
import z from "@deepseek-ai/schemastery";
import { installModelSelection } from "@deepseek-ai/dsh-agent";
import { createUserMessage } from "@deepseek-ai/dsh-llm";
import { SessionId } from "@deepseek-ai/dsh-session";
import { APPSERVER_STARTUP_SERVICE } from "./startup.js";
import { listPersistedSessions, loadPersistedSession } from "./persist.js";
import { serializeTurnStart } from "./turn-queue.js";
import { apiThreadListResult } from "./thread-list.js";
import {
	eventsFromSeq,
	eventsThroughTurn,
	isVisibleSession,
	messageOf,
	projectTurns,
	sessionSummary,
	textBlocks,
	toProtocolSeconds,
	turnID,
	turnStatusString,
	turnTimestamps,
} from "./translate.js";

/**
 * @module dsh-appserver — a codex app-server compatible stdio JSON-RPC
 * surface over dsh-base (M4 route A, formalized from the G0 spike).
 *
 * Backend capabilities are the canonical app-server methods minus approvals
 * and user input: dsh's permission model is sandbox presets, not interactive
 * server requests, so `approvals.v1` / `user-input.v1` are not advertised and
 * `approval.respond`-style interactions have no wire equivalent.
 */

/** Stable Cordis plugin name. */
export const name = "appserver-runner";

/** Services required before a thread can be created. */
export const inject = [APPSERVER_STARTUP_SERVICE, "agentDefaultModel", "agents", "sessions"];

const Config = z.object({ listen: z.string().required() });

const POLL_INTERVAL_MS = 200;

/** live registry: threadId -> { agent, cwd } */
const threads = new Map();

function writeLine(value) {
	process.stdout.write(JSON.stringify(value) + "\n");
}

function respond(id, result) {
	writeLine({ id, result });
}

function respondError(id, code, message) {
	writeLine({ id, error: { code, message } });
}

function notify(method, params) {
	writeLine({ method, params });
}

function firstText(input) {
	if (!Array.isArray(input)) return "";
	return input.map((part) => part?.text ?? "").join("");
}

function registryEntry(threadId) {
	const entry = threads.get(threadId);
	if (entry === void 0) return null;
	return entry;
}

/** Emit codex-style notifications from a DSH next-event cursor. */
function emitNewEvents(session, threadId, state) {
	for (const event of eventsThroughTurn(session.events, state.nextSeq, state.targetTurnId)) {
		state.nextSeq = event.seq + 1;
		switch (event.type) {
			case "turn/start":
				state.turnId = turnID(event);
				state.emit("turn/started", { threadId, turn: { id: state.turnId } });
				break;
			case "assistant/message": {
				const text = textBlocks(event.data?.message);
				if (text !== "") {
					state.emit("item/agentMessage/delta", {
						threadId,
						itemId: `item-${event.seq}`,
						delta: text,
					});
				}
			}
				break;
			case "turn/end": {
				const status = turnStatusString(event.data?.reason);
				state.emit("turn/completed", {
					threadId,
					turn: { id: state.turnId, status },
					status,
					reason: event.data?.reason?.kind ?? "completed",
				});
			}
				break;
			default:
				break;
		}
	}
}

function waitForTurnStart(agent, firstSeq) {
	return new Promise((resolve, reject) => {
		const findStart = () => eventsFromSeq(agent.session.events, firstSeq)
			.find((event) => event.type === "turn/start");
		const existing = findStart();
		if (existing !== void 0) {
			resolve(turnID(existing));
			return;
		}
		const timer = setInterval(() => {
			const started = findStart();
			if (started === void 0) return;
			clearInterval(timer);
			resolve(turnID(started));
		}, POLL_INTERVAL_MS);
		agent.whenIdle().then(() => {
			const started = findStart();
			clearInterval(timer);
			if (started !== void 0) {
				resolve(turnID(started));
				return;
			}
			reject(new Error("turn ended before a start event was recorded"));
		}, (error) => {
			clearInterval(timer);
			reject(error);
		});
	});
}

/** Stream one accepted turn after the RPC response establishes its route. */
async function streamTurn(ctx, agent, threadId, firstSeq, targetTurnId) {
	const state = { nextSeq: firstSeq, turnId: null, targetTurnId, emit: notify };
	const isComplete = () => eventsFromSeq(agent.session.events, firstSeq).some((event) =>
		event.type === "turn/end" && turnID(event) === targetTurnId);
	await new Promise((resolve, reject) => {
		let settled = false;
		const pump = () => {
			if (settled) return;
			emitNewEvents(agent.session, threadId, state);
			if (!isComplete()) return;
			settled = true;
			clearInterval(timer);
			resolve();
		};
		const timer = setInterval(pump, POLL_INTERVAL_MS);
		pump();
		agent.whenIdle().then(() => {
			pump();
			if (settled) return;
			settled = true;
			clearInterval(timer);
			reject(new Error(`turn ${targetTurnId} became idle without a turn/end event`));
		}, (error) => {
			if (settled) return;
			settled = true;
			clearInterval(timer);
			reject(error);
		});
	});
	await ctx.get("sessions").flush(agent.session);
}

async function ensureReady(ctx) {
	await ctx.get("loader")?.await();
}

async function createThread(ctx, cwd) {
	const selection = ctx.get("agentDefaultModel").currentSelection();
	const sessionId = SessionId(`session-${randomUUID()}`);
	const { agent } = await ctx.get("agents").create({
		sessionId,
		meta: { cwd },
		agentOptions: { provider: selection.provider, model: selection.model },
		setup: (agentCtx) => {
			installModelSelection(agentCtx, {
				current: selection,
				assembled: void 0,
			});
		},
	});
	await agent.whenIdle();
	threads.set(String(sessionId), { agent, cwd });
	return String(sessionId);
}

/** Resolve a thread's events: live session first, persisted log fallback. */
async function threadEvents(ctx, threadId) {
	const entry = registryEntry(threadId);
	if (entry !== null) {
		return {
			events: entry.agent.session.events,
			header: entry.agent.session.header ?? { cwd: entry.cwd },
			live: true,
		};
	}
	const persisted = await loadPersistedSession(ctx, threadId);
	if (persisted === null || persisted.error !== void 0) {
		return null;
	}
	return { events: persisted.events, header: persisted.header, live: false };
}

async function threadListResult(ctx, limit) {
	const projected = await apiThreadListResult(ctx, limit);
	if (projected !== null) return projected;
	const merged = new Map();
	for (const [threadId, entry] of threads) {
		if (!isVisibleSession(entry.agent.session.header)) continue;
		merged.set(threadId, {
			id: threadId,
			...sessionSummary(entry.agent.session.events, entry.agent.session.header ?? { cwd: entry.cwd }),
		});
	}
	const candidates = listPersistedSessions()
		.filter((persisted) => !merged.has(persisted.id))
		.sort((left, right) => right.updatedAt - left.updatedAt);
	for (const persisted of candidates) {
		if (limit > 0 && merged.size >= limit) {
			const cutoff = [...merged.values()]
				.sort((left, right) => right.updatedAt - left.updatedAt)[limit - 1]?.updatedAt ?? 0;
			if (persisted.updatedAt <= cutoff) break;
		}
		const loaded = await loadPersistedSession(ctx, persisted.id);
		if (loaded === null || loaded.error !== void 0) continue;
		if (!isVisibleSession(loaded.header)) continue;
		const summary = sessionSummary(loaded.events, loaded.header);
		merged.set(persisted.id, {
			id: persisted.id,
			...summary,
			updatedAt: summary.updatedAt || persisted.updatedAt,
		});
	}
	const data = [...merged.values()].sort((left, right) => right.updatedAt - left.updatedAt);
	if (limit > 0 && data.length > limit) data.length = limit;
	return { data, now: toProtocolSeconds(Date.now()) };
}

async function readThreadResult(ctx, threadId, includeTurns) {
	const resolved = await threadEvents(ctx, threadId);
	if (resolved === null) return null;
	const thread = {
		id: threadId,
		...sessionSummary(resolved.events, resolved.header),
	};
	if (includeTurns) {
		thread.turns = projectTurns(resolved.events);
	}
	return { thread };
}

async function turnsListResult(ctx, threadId, limit, sortDirection) {
	const resolved = await threadEvents(ctx, threadId);
	if (resolved === null) return null;
	const turns = projectTurns(resolved.events);
	const data = turns.map((turn) => {
		const timestamps = turnTimestamps(resolved.events, turn.id);
		return {
			id: turn.id,
			status: turnStatusString(turn.status),
			startedAt: timestamps.startedAt,
			completedAt: timestamps.completedAt,
			itemsView: "summary",
			items: turn.items.map((item) => ({
				id: item.id, type: item.type, text: item.text, status: "completed",
			})),
		};
	});
	if (sortDirection === "desc") data.reverse();
	if (limit > 0 && data.length > limit) data.length = limit;
	return { data, nextCursor: null };
}

async function startTurn(threadId, input) {
	const entry = registryEntry(threadId);
	if (entry === null) return { error: `unknown thread ${threadId}` };
	const text = firstText(input);
	if (text === "") return { error: "turn input text is required" };
	const firstSeq = entry.agent.session.seq;
	entry.agent.followup(createUserMessage({
		content: [{ type: "text", text }],
		source: { kind: "user" },
	}));
	const turnId = await waitForTurnStart(entry.agent, firstSeq);
	return {
		result: { turn: { id: turnId, threadId, status: "inProgress" } },
		stream: { agent: entry.agent, threadId, firstSeq, turnId },
	};
}

async function dispatch(ctx, msg) {
	const { id, method, params } = msg ?? {};
	const hasId = id !== void 0 && id !== null;
	try {
		switch (method) {
			case "initialize":
				await ensureReady(ctx);
				respond(id, {
					protocolVersion: 1,
					capabilities: {},
					serverInfo: { name: "dsh-appserver", version: "0.2.0" },
				});
				return;
			case "initialized":
				return;
			case "thread/list": {
				const limit = Number(params?.limit ?? 0) || 0;
				respond(id, await threadListResult(ctx, limit));
				return;
			}
			case "thread/start": {
				await ensureReady(ctx);
				const cwd = params?.cwd ?? process.cwd();
				const threadId = await createThread(ctx, cwd);
				const now = toProtocolSeconds(Date.now());
				respond(id, { thread: { id: threadId, cwd, createdAt: now, updatedAt: now } });
				return;
			}
			case "turn/start":
			case "turn/steer": {
				await ensureReady(ctx);
				const started = await serializeTurnStart(
					params?.threadId,
					() => startTurn(params?.threadId, params?.input),
				);
				if (started.error !== void 0) {
					respondError(id, "invalid_params", started.error);
					return;
				}
				respond(id, started.result);
				Promise.resolve().then(() => streamTurn(
					ctx, started.stream.agent, started.stream.threadId, started.stream.firstSeq, started.stream.turnId,
				))
					.catch((error) => notify("error", { message: String(error?.message ?? error) }));
				return;
			}
			case "thread/read": {
				const result = await readThreadResult(ctx, params?.threadId, params?.includeTurns === true);
				if (result === null) {
					respondError(id, "invalid_params", `unknown thread ${params?.threadId}`);
					return;
				}
				respond(id, result);
				return;
			}
			case "thread/turns/list": {
				const result = await turnsListResult(
					ctx, params?.threadId,
					Number(params?.limit ?? 0) || 0,
					params?.sortDirection ?? "asc",
				);
				if (result === null) {
					respondError(id, "invalid_params", `unknown thread ${params?.threadId}`);
					return;
				}
				respond(id, result);
				return;
			}
			case "turn/interrupt":
				respondError(id, "unsupported", "turn/interrupt is not mapped in the dsh backend v1; the turn keeps running on the Mac");
				return;
			default:
				respondError(id, "method_not_found", `unknown method ${method}`);
				return;
		}
	} catch (error) {
		if (hasId) respondError(id, "internal_error", String(error?.message ?? error));
	}
}

/**
 * Read JSON-RPC lines from stdin and keep the process alive until stdin
 * closes or the tree is disposed.
 * @param ctx - plugin context carrying the appserver startup service.
 */
export function apply(ctx, config) {
	const rl = readline.createInterface({ input: process.stdin });
	rl.on("line", (line) => {
		if (line.trim() === "") return;
		let msg;
		try {
			msg = JSON.parse(line);
		} catch {
			return;
		}
		dispatch(ctx, msg).catch((error) => {
			if (msg?.id !== void 0 && msg?.id !== null) {
				respondError(msg.id, "internal_error", String(error?.message ?? error));
			}
		});
	});
}

// Re-exported for tests: messageOf is the canonical user/assistant shape fix.
export { messageOf };
