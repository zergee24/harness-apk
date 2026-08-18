import { randomUUID } from "node:crypto";

function protocolSeconds(value) {
	return typeof value === "number" && Number.isFinite(value) ? Math.floor(value / 1000) : 0;
}

function projectedThread(item) {
	const values = item.projections?.values ?? {};
	const title = typeof values.title === "string" ? values.title.trim() : "";
	const promptAt = values.sessionListMetadata?.lastPromptAt;
	return {
		id: String(item.sessionId),
		name: title || "未命名会话",
		preview: "",
		cwd: typeof item.cwd === "string" ? item.cwd : "",
		updatedAt: protocolSeconds(typeof promptAt === "number" ? Math.max(item.updatedAt ?? 0, promptAt) : item.updatedAt),
		status: item.running === true ? { type: "active", activeFlags: [] } : { type: "idle" },
	};
}

function finish(items, limit) {
	const rows = items
		.filter((item) => item?.blank !== true && item?.origin !== "subagent")
		.map(projectedThread)
		.sort((left, right) => right.updatedAt - left.updatedAt);
	if (limit > 0 && rows.length > limit) rows.length = limit;
	return { data: rows, now: protocolSeconds(Date.now()) };
}

async function projectionServiceList(ctx, limit) {
	const persistence = ctx.get("sessionPersistence");
	if (persistence === void 0 || typeof persistence.list !== "function") return null;
	const cache = ctx.get("sessionProjectionCache");
	const agents = ctx.get("agents");
	const sessions = ctx.get("sessions");
	const byID = new Map();
	for (const meta of await persistence.list()) byID.set(String(meta.id), meta);
	if (typeof sessions?.list === "function") {
		for (const session of sessions.list()) byID.set(String(session.header.id), session.header);
	}
	const items = [];
	for (const meta of byID.values()) {
		if (meta.origin === "subagent" || meta.parentSession !== void 0) continue;
		let projections;
		try {
			projections = cache?.cachedSnapshot(meta);
		} catch {
			projections = void 0;
		}
		const listMetadata = projections?.values?.sessionListMetadata;
		items.push({
			sessionId: meta.id,
			updatedAt: typeof listMetadata?.lastPromptAt === "number"
				? Math.max(meta.createdAt ?? 0, listMetadata.lastPromptAt)
				: (meta.createdAt ?? 0),
			running: agents?.get?.(meta.id)?.status === "running",
			// cached blank:true is only a prefix hint for cold sessions; without
			// an authoritative small-log probe, conservatively keep the row visible.
			blank: false,
			origin: meta.origin,
			cwd: meta.cwd,
			projections,
		});
	}
	return finish(items, limit);
}

/** Map DSH's official zero-log session list surfaces into Codex thread rows. */
export async function apiThreadListResult(ctx, limit) {
	const api = ctx.get("apiProxy");
	if (api === void 0 || typeof api.sessions?.list !== "function") {
		return projectionServiceList(ctx, limit);
	}
	const rpcId = randomUUID();
	const response = await api.sessions.list({ rpcId, payload: { cursor: void 0 } });
	if (response?.result?.ok !== true) {
		throw new Error(response?.result?.error?.message ?? "DSH session.list failed");
	}
	return finish(response.result.value?.items ?? [], limit);
}
