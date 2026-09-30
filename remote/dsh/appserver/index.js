import { randomUUID } from "node:crypto";
import readline from "node:readline";
import z from "@deepseek-ai/schemastery";
import { installModelSelection } from "@deepseek-ai/dsh-agent";
import { createUserMessage } from "@deepseek-ai/dsh-llm";
import { SessionId } from "@deepseek-ai/dsh-session";
import { APPSERVER_STARTUP_SERVICE } from "./startup.js";
import {
	SessionUnavailableError,
	isSessionNotFound,
	isWriteOwnershipConflict,
	observeSessionEvents,
} from "./persist.js";
import { apiThreadListResult, forgetHydration } from "./thread-list.js";
import { serializeTurnStart } from "./turn-queue.js";
import { messageOf, projectTurns, textBlocks, turnID, turnStatusString } from "./translate.js";

/**
 * @module dsh-appserver — a codex app-server compatible stdio JSON-RPC
 * surface over dsh-base (M4 route A, formalized from the G0 spike).
 *
 * Backend capabilities are the canonical app-server methods minus approvals
 * and user input: dsh's permission model is sandbox presets, not interactive
 * server requests, so `approvals.v1` / `user-input.v1` are not advertised and
 * `approval.respond`-style interactions have no wire equivalent.
 *
 * Sessions created by other surfaces — the Desktop app in particular — are
 * first-class threads here. `thread/resume` adopts one through dsh's own
 * `ctx.agents.resume()`, which takes the session's write ownership and
 * continues the same persisted log; until then the session is readable through
 * `ctx.sessionQuery` without taking that ownership.
 */

/** Stable Cordis plugin name. */
export const name = "appserver-runner";

/** Services required before a thread can be created or resumed. */
export const inject = [APPSERVER_STARTUP_SERVICE, "agentDefaultModel", "agents", "sessions", "sessionQuery"];

const Config = z.object({ listen: z.string().required() });

const POLL_INTERVAL_MS = 200;

/**
 * How long `turn/start` waits for the driver to open its turn. The driver
 * opens a turn synchronously on wake, so this only covers scheduling latency;
 * a longer wait would turn a stuck driver into a blocked RPC.
 */
const TURN_OPEN_TIMEOUT_MS = 2000;

/** live registry: threadId -> { agent, cwd, name, updatedAt } */
const threads = new Map();

function writeLine(value) {
	process.stdout.write(JSON.stringify(value) + "\n");
}

function respond(id, result) {
	writeLine({ id, result });
}

function respondError(id, code, message, data) {
	writeLine({ id, error: data === void 0 ? { code, message } : { code, message, data } });
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

/** Milliseconds → Unix seconds, the unit the mobile client reads. */
function protocolSeconds(value) {
	return typeof value === "number" && Number.isFinite(value) ? Math.floor(value / 1000) : 0;
}

/** Shared thread projection: the shape both a fresh and a resumed thread return. */
function threadProjection(threadId, cwd, updatedAtSeconds) {
	return {
		id: threadId,
		cwd: cwd ?? "",
		name: threadId,
		preview: "",
		updatedAt: updatedAtSeconds,
		status: { type: "idle" },
	};
}

/**
 * Emit codex-style notifications for dsh session events after `lastSeq`.
 *
 * The cursor advances through events emitted so far, so a later poll picks up
 * everything appended in the meantime and the final drain after quiescence
 * cannot lose a `turn/end`.
 */
function emitNewEvents(session, threadId, state) {
	for (const event of session.snapshotEvents()) {
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
					turn: { id: state.turnId, status: turnStatusString(event.data?.reason) },
					status: turnStatusString(event.data?.reason),
					reason: event.data?.reason?.kind ?? "completed",
				});
				break;
			default:
				break;
		}
	}
}

/**
 * Stream one accepted turn until the agent reaches quiescence.
 *
 * `turn/start` answers as soon as the driver accepts the prompt; this keeps
 * emitting `item/agentMessage/delta` and closes with `turn/completed`, so the
 * phone can render progress and enable its stop button.
 */
async function streamTurn(agent, threadId, firstSeq) {
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

/**
 * The turn id a followup is about to open. The driver opens the turn
 * synchronously on wake, so this usually resolves on the first poll; the
 * bounded wait reports the prompt as accepted without a turn id rather than
 * failing a request the driver has already taken.
 * @returns the turn id, or null when no turn opened in time.
 */
function waitForOpenedTurn(agent, firstSeq) {
	const find = () => {
		for (const event of agent.session.snapshotEvents()) {
			if (event.seq < firstSeq || event.type !== "turn/start") continue;
			return turnID(event);
		}
		return null;
	};
	const existing = find();
	if (existing !== null) return Promise.resolve(existing);
	return new Promise((resolve) => {
		const deadline = Date.now() + TURN_OPEN_TIMEOUT_MS;
		const timer = setInterval(() => {
			const found = find();
			if (found === null && Date.now() < deadline) return;
			clearInterval(timer);
			resolve(found);
		}, POLL_INTERVAL_MS);
	});
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
	threads.set(String(sessionId), { agent, cwd, name: "", updatedAt: protocolSeconds(Date.now()) });
	return String(sessionId);
}

/**
 * Adopt an existing session into this process so its turn can be driven here.
 *
 * `ctx.agents.resume()` takes the session's write ownership and continues the
 * same persisted log, which is what makes the phone share one conversation
 * with the Desktop app instead of starting a parallel one. Ownership is
 * exclusive per session: a session Desktop currently holds stays a readable
 * history until Desktop releases it, and this reports that as a distinct,
 * user-explainable failure instead of a raw syscall error.
 * @param ctx - plugin context carrying `agents`.
 * @param threadId - session identity from the wire.
 * @returns `{entry}` on success or `{error}` with a stable code.
 */
async function adoptThread(ctx, threadId) {
	const existing = registryEntry(threadId);
	if (existing !== null) return { entry: existing };
	const agents = ctx.get("agents");
	if (typeof agents?.resume !== "function") {
		return { error: new SessionUnavailableError("this appserver cannot resume sessions", "resume_unavailable") };
	}
	try {
		const handle = await agents.resume({ resumeSessionId: SessionId(String(threadId)) });
		const entry = {
			agent: handle.agent,
			cwd: handle.agent.session.header.cwd ?? "",
			name: "",
			updatedAt: protocolSeconds(Date.now()),
			handle,
		};
		threads.set(String(threadId), entry);
		forgetHydration(threadId);
		return { entry };
	} catch (error) {
		if (isWriteOwnershipConflict(error)) {
			return {
				error: new SessionUnavailableError(
					`会话正在 Mac 桌面端使用中，暂时无法从手机接管（会话 ${threadId}）。` +
						"请在桌面端离开该会话后重试。",
					"thread_locked",
				),
			};
		}
		if (isSessionNotFound(error)) {
			return { error: new SessionUnavailableError(`unknown thread ${threadId}`, "thread_not_found") };
		}
		return { error };
	}
}

/** Resolve a thread's events: the adopted session first, a read-only view second. */
async function threadEvents(ctx, threadId) {
	const entry = registryEntry(threadId);
	if (entry !== null) {
		// `Session` exposes no `events` property in this dsh generation; the
		// documented reader returns a frozen current snapshot.
		return { events: entry.agent.session.snapshotEvents(), cwd: entry.cwd, name: entry.name, live: true };
	}
	const observed = await observeSessionEvents(ctx, threadId);
	if (observed === null) return null;
	return {
		events: observed.events,
		cwd: observed.header.cwd ?? "",
		name: "",
		live: false,
	};
}

async function readThreadResult(ctx, threadId, includeTurns) {
	const resolved = await threadEvents(ctx, threadId);
	if (resolved === null) return null;
	const thread = threadProjection(threadId, resolved.cwd, protocolSeconds(Date.now()));
	if (includeTurns) thread.turns = projectTurns(resolved.events);
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
				timestamps.startedAt = protocolSeconds(event.timestamp ?? null);
			}
			if (event.type === "turn/end") timestamps.completedAt = protocolSeconds(event.timestamp ?? null);
		}
		return {
			id: turn.id,
			status: turn.status,
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

/**
 * Accept one turn for a thread, adopting a persisted session when needed.
 * @returns `{threadId, agent, firstSeq}` or `{error}`.
 */
async function acceptTurn(ctx, threadId, input) {
	const text = firstText(input);
	if (text === "") return { error: new SessionUnavailableError("turn input text is required", "invalid_input") };
	const adopted = await adoptThread(ctx, threadId);
	if (adopted.error !== void 0) return { error: adopted.error };
	const entry = adopted.entry;
	if (entry.name === "") entry.name = text.slice(0, 60);
	entry.updatedAt = protocolSeconds(Date.now());
	const firstSeq = entry.agent.session.seq;
	entry.agent.followup(createUserMessage({
		content: [{ type: "text", text }],
		source: { kind: "user" },
	}));
	return { threadId, agent: entry.agent, firstSeq };
}

/**
 * Accept one steering message for the active turn. Steering targets the next
 * step of the turn already running, so it is never queued behind one.
 */
async function acceptSteer(ctx, threadId, input) {
	const text = firstText(input);
	if (text === "") return { error: new SessionUnavailableError("turn input text is required", "invalid_input") };
	const entry = registryEntry(threadId);
	if (entry === null) {
		return { error: new SessionUnavailableError(`thread ${threadId} is not running here`, "thread_not_found") };
	}
	const firstSeq = entry.agent.session.seq;
	entry.agent.steer(createUserMessage({
		content: [{ type: "text", text }],
		source: { kind: "user" },
	}));
	return { threadId, agent: entry.agent, firstSeq };
}

/** Map a thrown or returned failure to a JSON-RPC error code. */
function errorCodeOf(error) {
	if (error?.code !== void 0) return error.code;
	return "internal_error";
}

/** Report a failed thread operation with its stable code. */
function respondFailure(id, error) {
	if (error instanceof SessionUnavailableError || error?.code !== void 0) {
		respondError(id, errorCodeOf(error), String(error.message ?? error));
		return;
	}
	// An unexpected failure keeps its stack on the wire: the bridge logs the
	// JSON-RPC error verbatim, so the trace is what makes a report actionable.
	respondError(id, "internal_error", String(error?.message ?? error), {
		stack: typeof error?.stack === "string" ? error.stack.split("\n").slice(0, 8) : void 0,
	});
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
					serverInfo: { name: "dsh-appserver", version: "0.3.0" },
				});
				return;
			case "initialized":
				return;
			case "thread/list": {
				const limit = Number(params?.limit ?? 0) || 0;
				respond(id, await apiThreadListResult(ctx, limit));
				return;
			}
			case "thread/start": {
				await ensureReady(ctx);
				const cwd = params?.cwd ?? process.cwd();
				const threadId = await createThread(ctx, cwd);
				respond(id, { thread: threadProjection(threadId, cwd, protocolSeconds(Date.now())) });
				return;
			}
			case "thread/resume": {
				await ensureReady(ctx);
				const threadId = String(params?.threadId ?? "");
				if (threadId === "") {
					respondError(id, "invalid_params", "thread/resume threadId is required");
					return;
				}
				const adopted = await adoptThread(ctx, threadId);
				if (adopted.error !== void 0) {
					respondFailure(id, adopted.error);
					return;
				}
				respond(id, { thread: threadProjection(threadId, adopted.entry.cwd, protocolSeconds(Date.now())) });
				return;
			}
			case "turn/start":
			case "turn/steer": {
				await ensureReady(ctx);
				const threadId = String(params?.threadId ?? "");
				if (threadId === "") {
					respondError(id, "invalid_params", `${method} threadId is required`);
					return;
				}
				const accepting = method === "turn/steer" ? acceptSteer : acceptTurn;
				const accepted = await serializeTurnStart(threadId, () => accepting(ctx, threadId, params?.input));
				if (accepted.error !== void 0) {
					respondFailure(id, accepted.error);
					return;
				}
				const turnId = await waitForOpenedTurn(accepted.agent, accepted.firstSeq);
				respond(id, { turn: { id: turnId, threadId, status: "inProgress" } });
				// The turn is accepted: stream it in the background so this RPC
				// answers immediately and the phone can render progress.
				streamTurn(accepted.agent, threadId, accepted.firstSeq)
					.catch((error) => notify("error", { message: String(error?.message ?? error) }));
				return;
			}
			case "turn/interrupt": {
				const threadId = String(params?.threadId ?? "");
				const entry = registryEntry(threadId);
				if (entry === null) {
					// Nothing is running here. Report it as such instead of
					// pretending to have stopped work this process does not own.
					respondError(id, "thread_not_found", `no live turn for thread ${threadId} in this backend`);
					return;
				}
				// Same call the Desktop stop button makes: abort the active
				// activity, keep queued input. The receipt means the signal was
				// admitted, not that the agent has already reached quiescence.
				entry.agent.cancel({ kind: "user" }, { keepInbox: true });
				respond(id, { accepted: true, threadId });
				return;
			}
			case "thread/read": {
				const result = await readThreadResult(ctx, params?.threadId, params?.includeTurns === true);
				if (result === null) {
					respondError(id, "thread_not_found", `unknown thread ${params?.threadId}`);
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
					respondError(id, "thread_not_found", `unknown thread ${params?.threadId}`);
					return;
				}
				respond(id, result);
				return;
			}
			default:
				respondError(id, "method_not_found", `unknown method ${method}`);
				return;
		}
	} catch (error) {
		if (hasId) respondFailure(id, error);
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
