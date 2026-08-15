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

/** Map a dsh turn/end reason to a codex turn status object. */
export function turnStatusFromReason(reason) {
	const kind = reason?.kind ?? "completed";
	return { type: kind === "completed" ? "completed" : "failed" };
}

/** String status used by the mobile `thread/turns/list` summary view. */
export function turnStatusString(reason) {
	const kind = reason?.kind ?? "completed";
	return kind === "completed" ? "completed" : "failed";
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

/** Millisecond timestamps of a turn from its start/end events. */
export function turnTimestamps(events, turnId) {
	let startedAt = null;
	let completedAt = null;
	for (const event of events) {
		if (turnID(event) !== turnId) continue;
		if (event.type === "turn/start" && startedAt === null) startedAt = event.timestamp ?? null;
		if (event.type === "turn/end") completedAt = event.timestamp ?? null;
	}
	return { startedAt, completedAt };
}
