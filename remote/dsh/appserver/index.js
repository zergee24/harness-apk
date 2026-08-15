import { randomUUID } from "node:crypto";
import readline from "node:readline";
import z from "@deepseek-ai/schemastery";
import { installModelSelection } from "@deepseek-ai/dsh-agent";
import { createUserMessage } from "@deepseek-ai/dsh-llm";
import { SessionId } from "@deepseek-ai/dsh-session";
import { APPSERVER_STARTUP_SERVICE } from "./startup.js";
import { listPersistedSessions, loadPersistedSession } from "./persist.js";
import { messageOf, projectTurns, textBlocks, turnID, turnStatusFromReason, turnStatusString } from "./translate.js";

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

/** live registry: threadId -> { agent, cwd, name, updatedAt } */
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

/** Emit codex-style notifications for dsh session events after lastSeq. */
function emitNewEvents(session, threadId, state) {
	for (const event of session.events) {
		if (event.seq <= state.lastSeq) continue;
		state.lastSeq = event.seq;
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
			case "turn/end":
				state.emit("turn/completed", {
					threadId,
					turn: { id: state.turnId, status: "completed" },
					status: turnStatusString(event.data?.reason),
					reason: event.data?.reason?.kind ?? "completed",
				});
				break;
			default:
				break;
		}
	}
}

/** Wait for quiescence while streaming session events as notifications. */
async function runTurn(agent, threadId, firstSeq) {
	const state = { lastSeq: firstSeq, turnId: null, emit: notify };
	const timer = setInterval(() => emitNewEvents(agent.session, threadId, state), POLL_INTERVAL_MS);
	try {
		await agent.whenIdle();
	} finally {
		clearInterval(timer);
		emitNewEvents(agent.session, threadId, state);
	}
	return state.turnId;
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
	threads.set(String(sessionId), { agent, cwd, name: "", updatedAt: Date.now() });
	return String(sessionId);
}

/** Resolve a thread's events: live session first, persisted log fallback. */
async function threadEvents(ctx, threadId) {
	const entry = registryEntry(threadId);
	if (entry !== null) {
		return { events: entry.agent.session.events, cwd: entry.cwd, name: entry.name, live: true };
	}
	const persisted = await loadPersistedSession(ctx, threadId);
	if (persisted === null || persisted.error !== void 0) {
		return null;
	}
	return {
		events: persisted.events, cwd: persisted.cwd ?? null, name: null, live: false,
	};
}

function threadListResult(limit) {
	const now = Date.now();
	const merged = new Map();
	for (const [threadId, entry] of threads) {
		merged.set(threadId, {
			id: threadId, name: entry.name || threadId, preview: "",
			cwd: entry.cwd ?? "", updatedAt: entry.updatedAt, status: { type: "idle" },
		});
	}
	for (const persisted of listPersistedSessions()) {
		if (merged.has(persisted.id)) continue;
		merged.set(persisted.id, {
			id: persisted.id, name: persisted.cwd || persisted.id, preview: "",
			cwd: persisted.cwd ?? "", updatedAt: persisted.updatedAt, status: { type: "idle" },
		});
	}
	const data = [...merged.values()].sort((left, right) => right.updatedAt - left.updatedAt);
	if (limit > 0 && data.length > limit) {
		data.length = limit;
	}
	return { data, now };
}

async function readThreadResult(ctx, threadId, includeTurns) {
	const resolved = await threadEvents(ctx, threadId);
	if (resolved === null) return null;
	const thread = {
		id: threadId,
		cwd: resolved.cwd ?? "",
		name: resolved.name ?? threadId,
		preview: "",
		updatedAt: Date.now(),
		status: { type: "idle" },
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
		const timestamps = { startedAt: null, completedAt: null };
		for (const event of resolved.events) {
			if (turnID(event) !== turn.id) continue;
			if (event.type === "turn/start" && timestamps.startedAt === null) {
				timestamps.startedAt = event.timestamp ?? null;
			}
			if (event.type === "turn/end") timestamps.completedAt = event.timestamp ?? null;
		}
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

async function startTurn(ctx, threadId, input) {
	const entry = registryEntry(threadId);
	if (entry === null) return { error: `unknown thread ${threadId}` };
	const text = firstText(input);
	if (text === "") return { error: "turn input text is required" };
	if (entry.name === "") entry.name = text.slice(0, 60);
	entry.updatedAt = Date.now();
	const firstSeq = entry.agent.session.seq;
	entry.agent.followup(createUserMessage({
		content: [{ type: "text", text }],
		source: { kind: "user" },
	}));
	const turnId = await runTurn(entry.agent, threadId, firstSeq);
	await ctx.get("sessions").flush(entry.agent.session);
	return { turn: { id: turnId, threadId, status: "completed" } };
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
				respond(id, threadListResult(limit));
				return;
			}
			case "thread/start": {
				await ensureReady(ctx);
				const cwd = params?.cwd ?? process.cwd();
				const threadId = await createThread(ctx, cwd);
				const now = Date.now();
				respond(id, { thread: { id: threadId, cwd, createdAt: now, updatedAt: now } });
				return;
			}
			case "turn/start":
			case "turn/steer": {
				await ensureReady(ctx);
				const result = await startTurn(ctx, params?.threadId, params?.input);
				if (result.error !== void 0) {
					respondError(id, "invalid_params", result.error);
					return;
				}
				respond(id, result);
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
