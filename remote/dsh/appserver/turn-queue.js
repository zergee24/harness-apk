/**
 * @module dsh-appserver/turn-queue — serialize turn work per thread.
 *
 * A dsh agent accepts one activity at a time: `followup()` appends a prompt to
 * the durable inbox and wakes the driver. Two concurrent `turn/start` calls for
 * the same thread would therefore race on inbox order and event cursors, and
 * each would stream the other's events. Chaining per thread keeps the wire
 * contract — one accepted turn at a time — without serializing other threads.
 */

/** threadId → tail of that thread's operation chain. */
const chains = new Map();

/**
 * Run `operation` after every previously queued operation for `threadId`.
 * @param threadId - the thread whose turns must not overlap.
 * @param operation - the work to serialize.
 * @returns the operation's own result or rejection.
 */
export function serializeTurnStart(threadId, operation) {
	const key = String(threadId ?? "");
	const previous = chains.get(key) ?? Promise.resolve();
	const started = previous.catch(() => {}).then(operation);
	const tracked = started.finally(() => {
		if (chains.get(key) === tracked) chains.delete(key);
	});
	chains.set(key, tracked);
	return tracked;
}
