/**
 * @module dsh-appserver/translate — dsh session events → codex app-server
 * thread/turn/item shapes.
 */

/** Join the text blocks of a dsh message. */
export function textBlocks(message) {
	const blocks = message?.content ?? [];
	return blocks.filter((block) => block.type === "text").map((block) => block.text).join("");
}

/**
 * The message record carried by a session event. dsh shapes differ by event
 * type: `user/message` events ARE the message record, `assistant/message`
 * events nest it under `data.message`.
 * @returns the message record, or null.
 */
export function messageOf(event) {
	if (event.type === "user/message") return event.data;
	if (event.type === "assistant/message") return event.data?.message;
	return null;
}

/** Stable turn id from a dsh turn/start event (dsh turn ids are numbers). */
export function turnID(event) {
	const turn = event.data?.turn;
	if (typeof turn === "object" && turn !== null) return String(turn.id ?? turn);
	if (turn !== void 0 && turn !== null) return String(turn);
	return `turn-${event.seq}`;
}

/** Normalize a dsh reason or codex-style status object for the mobile contract. */
export function turnStatusString(value) {
	const kind = value?.kind ?? value?.type;
	if (kind === "completed") return "completed";
	if (kind === "aborted" || kind === "interrupted" || kind === "cancelled" || kind === "canceled") {
		return "interrupted";
	}
	if (kind === "inProgress" || kind === "running") return "inProgress";
	return "failed";
}

/** Map a dsh turn/end reason to a codex turn status object. */
export function turnStatusFromReason(reason) {
	return { type: turnStatusString(reason) };
}

/** Convert a DSH epoch-millisecond value to canonical integer Unix seconds. */
export function toProtocolSeconds(value) {
	return typeof value === "number" && Number.isFinite(value) ? Math.floor(value / 1000) : null;
}

/** Events at or after a captured Session.seq next-event cursor. */
export function eventsFromSeq(events, nextSeq) {
	return events.filter((event) => event.seq >= nextSeq);
}

/** One turn's event window, excluding any later queued turns. */
export function eventsThroughTurn(events, nextSeq, targetTurnId) {
	const startIndex = events.findIndex((event) =>
		event.type === "turn/start" && turnID(event) === String(targetTurnId));
	if (startIndex < 0) return [];
	const endOffset = events.slice(startIndex).findIndex((event) =>
		event.type === "turn/end" && turnID(event) === String(targetTurnId));
	const endIndex = endOffset < 0 ? events.length : startIndex + endOffset + 1;
	return events.slice(startIndex, endIndex).filter((event) => event.seq >= nextSeq);
}

/**
 * Project session events into codex turns: `[{id, status, items}]`.
 * @param events - dsh session events (`.seq`, `.type`, `.data`).
 * @param firstSeq - only project events at or after this seq.
 */
export function projectTurns(events, firstSeq = 0) {
	const turns = [];
	let current = null;
	for (const event of events) {
		if (event.seq < firstSeq) continue;
		if (event.type === "turn/start") {
			current = { id: turnID(event), status: { type: "inProgress" }, items: [] };
			turns.push(current);
			continue;
		}
		if (current === null) continue;
		if (event.type === "user/message") {
			if (event.data?.source?.kind !== "user") continue;
			const text = textBlocks(event.data);
			if (text === "") continue;
			current.items.push({
				id: `item-${event.seq}`, type: "userMessage",
				text, status: "completed",
			});
		} else if (event.type === "assistant/message") {
			const message = event.data?.message;
			if (!message) continue;
			const text = textBlocks(message);
			if (text === "") continue; // tool-call-only messages carry no text
			current.items.push({
				id: `item-${event.seq}`, type: "agentMessage",
				text, status: "completed",
			});
		} else if (event.type === "turn/end") {
			current.status = turnStatusFromReason(event.data?.reason);
		}
	}
	return turns;
}

/** Canonical Unix-second timestamps of a turn from its start/end events. */
export function turnTimestamps(events, turnId) {
	let startedAt = null;
	let completedAt = null;
	for (const event of events) {
		if (turnID(event) !== turnId) continue;
		if (event.type === "turn/start" && startedAt === null) startedAt = toProtocolSeconds(event.time);
		if (event.type === "turn/end") completedAt = toProtocolSeconds(event.time);
	}
	return { startedAt, completedAt };
}

function meaningfulUserText(event) {
	if (event.type !== "user/message" || event.data?.source?.kind !== "user") return "";
	return textBlocks(event.data).trim();
}

/** Internal subagent sessions should not appear as top-level mobile threads. */
export function isVisibleSession(header = {}) {
	return header.origin !== "subagent";
}

/** Build user-facing thread metadata from one restored DSH session. */
export function sessionSummary(events, header = {}) {
	const humanMessages = events.map(meaningfulUserText).filter(Boolean);
	const turns = projectTurns(events);
	const latestTurn = turns.at(-1);
	const latestEventTime = events.reduce((latest, event) => Math.max(latest, event.time ?? 0), 0);
	return {
		name: humanMessages[0]?.slice(0, 60) || "未命名会话",
		preview: humanMessages.at(-1)?.slice(0, 240) || "",
		cwd: header.cwd ?? "",
		updatedAt: toProtocolSeconds(latestEventTime || header.createdAt) ?? 0,
		status: latestTurn?.status.type === "inProgress"
			? { type: "active", activeFlags: [] }
			: { type: "idle" },
	};
}
