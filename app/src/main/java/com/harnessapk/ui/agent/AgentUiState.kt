package com.harnessapk.ui.agent

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import java.util.Locale

internal fun canStartAgent(agent: Agent): Boolean = agent.status == AgentStatus.READY

internal fun agentStatusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.READY -> "可用"
    AgentStatus.WAITING_FOR_CORPUS -> "缺少资料"
    AgentStatus.DISABLED -> "已停用"
    AgentStatus.DRAFT -> "草稿"
    AgentStatus.FAILED -> "不可用"
}

internal fun agentCorpusCoverage(agent: Agent): String =
    "资料 ${agent.installedCorpusCount}/${agent.requiredCorpusCount}"

internal fun formatAgentPackageSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
