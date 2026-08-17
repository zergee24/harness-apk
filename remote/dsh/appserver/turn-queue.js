const turnStartChains = new Map();

export function serializeTurnStart(threadId, operation) {
	const previous = turnStartChains.get(threadId) ?? Promise.resolve();
	const started = previous.catch(() => {}).then(operation);
	const tracked = started.finally(() => {
		if (turnStartChains.get(threadId) === tracked) {
			turnStartChains.delete(threadId);
		}
	});
	turnStartChains.set(threadId, tracked);
	return tracked;
}
