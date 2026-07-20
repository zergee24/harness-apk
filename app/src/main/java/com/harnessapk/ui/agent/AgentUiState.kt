package com.harnessapk.ui.agent

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.V2Authorship
import com.harnessapk.agent.V2InstallClass
import com.harnessapk.agent.V2PackageType
import com.harnessapk.agent.V2SourceGenre
import com.harnessapk.agent.V2SourceRecord
import java.util.Locale

internal data class AgentInstallationPackage(
    val id: String,
    val type: V2PackageType,
    val installClass: V2InstallClass,
    val sizeBytes: Long,
)

internal data class AgentInstallationProfile(
    val id: String,
    val packageIds: List<String>,
)

internal data class AgentInstallationPlan(
    val agentPackageId: String,
    val agentSizeBytes: Long,
    val packages: List<AgentInstallationPackage>,
    val profiles: List<AgentInstallationProfile>,
    val requiredPackageIds: List<String>,
)

internal sealed interface AgentInstallationDecision {
    data class InstallDirectly(val profileId: String) : AgentInstallationDecision

    data class ShowAdjustment(
        val selectedProfileId: String,
        val suggestedProfileId: String,
        val reason: String,
    ) : AgentInstallationDecision

    data class BlockMissingRequired(val missingPackageIds: List<String>) : AgentInstallationDecision
}

internal fun installationDecision(
    plan: AgentInstallationPlan,
    availableBytes: Long,
    requestedProfileId: String?,
): AgentInstallationDecision {
    val blocked = AgentInstallationDecision.BlockMissingRequired(plan.requiredPackageIds.distinct().sorted())
    if (availableBytes < 0L || plan.agentPackageId.isBlank() || plan.agentSizeBytes <= 0L) return blocked
    if (plan.requiredPackageIds.isEmpty() || plan.requiredPackageIds.distinct().size != plan.requiredPackageIds.size) {
        return blocked
    }
    if (plan.packages.any { it.id.isBlank() || it.sizeBytes <= 0L }) return blocked
    val packages = plan.packages.associateBy(AgentInstallationPackage::id)
    if (packages.size != plan.packages.size || plan.requiredPackageIds.any { it !in packages }) return blocked
    if (plan.requiredPackageIds.any { packages.getValue(it).installClass != V2InstallClass.REQUIRED }) return blocked

    val profiles = plan.profiles.associateBy(AgentInstallationProfile::id)
    if (profiles.size != plan.profiles.size || profiles.keys != INSTALLATION_PROFILE_ORDER.toSet()) return blocked
    if (plan.profiles.any { profile ->
            profile.packageIds.distinct().size != profile.packageIds.size ||
                profile.packageIds.any { it !in packages } ||
                plan.requiredPackageIds.any { it !in profile.packageIds }
        }
    ) {
        return blocked
    }

    fun exactBytes(profileId: String): Long? {
        var total = plan.agentSizeBytes
        profiles.getValue(profileId).packageIds.forEach { packageId ->
            val packageBytes = packages.getValue(packageId).sizeBytes
            if (total > Long.MAX_VALUE - packageBytes) return null
            total += packageBytes
        }
        return total
    }

    val selectedProfileId = requestedProfileId ?: DEFAULT_INSTALLATION_PROFILE_ID
    val selectedIndex = INSTALLATION_PROFILE_ORDER.indexOf(selectedProfileId)
    if (selectedIndex < 0) return blocked
    val selectedBytes = exactBytes(selectedProfileId) ?: return blocked
    if (selectedBytes <= availableBytes) {
        return AgentInstallationDecision.InstallDirectly(selectedProfileId)
    }

    val suggested = INSTALLATION_PROFILE_ORDER
        .take(selectedIndex)
        .asReversed()
        .firstOrNull { profileId -> exactBytes(profileId)?.let { it <= availableBytes } == true }
        ?: return blocked
    return AgentInstallationDecision.ShowAdjustment(
        selectedProfileId = selectedProfileId,
        suggestedProfileId = suggested,
        reason = if (selectedProfileId == DEFAULT_INSTALLATION_PROFILE_ID) {
            "推荐安装空间不足"
        } else {
            "所选资料空间不足"
        },
    )
}

internal fun exactInstallationBytes(plan: AgentInstallationPlan, profileId: String): Long? =
    when (val decision = installationDecision(plan, Long.MAX_VALUE, profileId)) {
        is AgentInstallationDecision.InstallDirectly -> {
            val packages = plan.packages.associateBy(AgentInstallationPackage::id)
            plan.profiles.first { it.id == decision.profileId }.packageIds.fold(plan.agentSizeBytes) { total, id ->
                Math.addExact(total, packages.getValue(id).sizeBytes)
            }
        }
        else -> null
    }

internal fun sourceParticipationLabel(): String = "仅阅读核验，不参与回答"

internal fun shouldShowWrittenPersonaWarning(sources: List<V2SourceRecord>): Boolean = sources.none { source ->
    source.genre in CONVERSATIONAL_SOURCE_GENRES && source.authorship in DIRECT_SOURCE_AUTHORSHIP
}

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

internal val INSTALLATION_PROFILE_ORDER = listOf("lite", "balanced", "complete", "source")
internal const val DEFAULT_INSTALLATION_PROFILE_ID = "balanced"

private val CONVERSATIONAL_SOURCE_GENRES = setOf(
    V2SourceGenre.SPEECH,
    V2SourceGenre.CONVERSATION,
    V2SourceGenre.LETTER,
    V2SourceGenre.INTERVIEW,
)
private val DIRECT_SOURCE_AUTHORSHIP = setOf(V2Authorship.DIRECT, V2Authorship.EDITED_DIRECT)
