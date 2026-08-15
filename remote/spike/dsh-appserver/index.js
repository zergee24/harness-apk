import { randomUUID } from "node:crypto";
import readline from "node:readline";
import z from "@deepseek-ai/schemastery";
import { installModelSelection } from "@deepseek-ai/dsh-agent";
import { createUserMessage } from "@deepseek-ai/dsh-llm";
import { SessionId } from "@deepseek-ai/dsh-session";
import { APPSERVER_STARTUP_SERVICE } from "./startup.js";

/**
 * @module dsh-appserver-spike — M4 G0 spike: a codex app-server compatible
 * stdio JSON-RPC surface over dsh-base (route A).
 *
 * Proves that dsh agent/session APIs can be wrapped in the canonical
 * app-server protocol: thread/start, turn/start, turn/steer, thread/read,
 * thread/list and turn-level notifications. Interrupt and approval mapping
 * are explicit gaps recorded for G2.
 */

/** Stable Cordis plugin name. */
export const name = "appserver-runner";

/** Services required before a thread can be created. */
export const inject = [APPSERVER_STARTUP_SERVICE, "agentDefaultModel", "agents", "sessions"];

const Config = z.object({ listen: z.string().required() });

/** in-memory registry: threadId -> { agent, cwd, name, updatedAt } */
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

function textBlocks(message) {
	const blocks = message?.content ?? [];
	return blocks.filter((block) => block.type === "text").map((block) => block.text).join("");
}

/** Map dsh session events (after firstSeq) to app-server notifications. */
function emitNewEvents(session, threadId, state) {
	for (const event of session.events) {
		if (event.seq <= state.lastSeq) continue;
		state.lastSeq = event.seq;
		switch (event.type) {
			case "turn/start":
				state.turnId = event.data?.turn?.id ?? event.data?.turn ?? `turn-${event.seq}`;
				state.emit(`turn/started`, { threadId, turn: { id: state.turnId } });
				break;
			case "assistant/message": {
				const text = textBlocks(event.data?.message);
				if (text !== "") {
					state.emit(`item/agentMessage/delta`, {
						threadId,
						itemId: `item-${event.seq}`,
						delta: text,
					});
				}
			}
				break;
			case "turn/end":
				state.emit(`turn/completed`, {
					threadId,
					turn: { id: state.turnId },
					status: "completed",
					reason: event.data?.reason?.kind ?? "completed",
				});
				break;
			default:
				break;
		}
	}
}

/**
 * Wait for agent quiescence while streaming session events as notifications.
 * @param agent - the dsh agent whose session is watched.
 * @param threadId - the app-server thread identity.
 * @param firstSeq - session seq at turn start.
 * @param emit - notification emitter (spike prints to stdout).
 */
async function runTurn(agent, threadId, firstSeq, emit) {
	const state = { lastSeq: firstSeq, turnId: null, emit };
	const timer = setInterval(() => emitNewEvents(agent.session, threadId, state), 200);
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
	threads.set(sessionId, { agent, cwd, name: "", updatedAt: Date.now() });
	return sessionId;
}

function readThread(threadId, includeTurns) {
	const entry = threads.get(threadId);
	if (entry === void 0) return null;
	const thread = {
		id: threadId,
		cwd: entry.cwd,
		createdAt: entry.updatedAt,
		updatedAt: entry.updatedAt,
	};
	if (!includeTurns) return { thread };
	const turns = [];
	let current = null;
	for (const event of entry.agent.session.events) {
		if (event.type === "turn/start") {
			current = {
				id: event.data?.turn?.id ?? event.data?.turn ?? `turn-${event.seq}`,
				status: { type: "inProgress" },
				items: [],
			};
			turns.push(current);
			continue;
		}
		if (current === null) continue;
		if (event.type === "user/message") {
			current.items.push({
				id: `item-${event.seq}`,
				type: "userMessage",
				text: textBlocks(event.data?.message),
				status: "completed",
			});
		} else if (event.type === "assistant/message") {
			current.items.push({
				id: `item-${event.seq}`,
				type: "agentMessage",
				text: textBlocks(event.data?.message),
				status: "completed",
			});
		} else if (event.type === "turn/end") {
			const kind = event.data?.reason?.kind ?? "completed";
			current.status = { type: kind === "completed" ? "completed" : "failed" };
		}
	}
	thread.turns = turns;
	return { thread };
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
					serverInfo: { name: "dsh-appserver-spike", version: "0.1.0" },
				});
				return;
			case "initialized":
				return;
			case "thread/list": {
				const data = [...threads.entries()].map(([threadId, entry]) => ({
					id: threadId,
					name: entry.name || threadId,
					preview: "",
					cwd: entry.cwd,
					updatedAt: entry.updatedAt,
					status: { type: "idle" },
				}));
				respond(id, { data });
				return;
			}
			case "thread/start": {
				await ensureReady(ctx);
				const cwd = params?.cwd ?? process.cwd();
				const threadId = await createThread(ctx, cwd);
				respond(id, {
					thread: { id: threadId, cwd, createdAt: Date.now(), updatedAt: Date.now() },
				});
				return;
			}
			case "turn/start":
			case "turn/steer": {
				await ensureReady(ctx);
				const threadId = params?.threadId;
				const entry = threads.get(threadId);
				if (entry === void 0) {
					respondError(id, "invalid_params", `unknown thread ${threadId}`);
					return;
				}
				const input = params?.input ?? [];
				const text = input.map((part) => part?.text ?? "").join("");
				if (text === "") {
					respondError(id, "invalid_params", "turn input text is required");
					return;
				}
				entry.name = entry.name || text.slice(0, 60);
				entry.updatedAt = Date.now();
				const firstSeq = entry.agent.session.seq;
				entry.agent.followup(createUserMessage({
					content: [{ type: "text", text }],
					source: { kind: "user" },
				}));
				const turnId = await runTurn(entry.agent, threadId, firstSeq, notify);
				await ctx.get("sessions").flush(entry.agent.session);
				respond(id, { turn: { id: turnId, threadId, status: "completed" } });
				return;
			}
			case "thread/read": {
				const result = readThread(params?.threadId, params?.includeTurns === true);
				if (result === null) {
					respondError(id, "invalid_params", `unknown thread ${params?.threadId}`);
					return;
				}
				respond(id, result);
				return;
			}
			case "turn/interrupt":
				respondError(id, "unsupported", "turn/interrupt is not mapped in the G0 spike; see G2");
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
 * @param config - validated config.
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
