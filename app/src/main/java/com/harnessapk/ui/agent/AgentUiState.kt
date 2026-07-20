package com.harnessapk.ui.agent

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentInstallResult
import com.harnessapk.agent.AgentInsufficientStorageException
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.InstallContractPackage
import com.harnessapk.agent.InstallContractProfile
import com.harnessapk.agent.V2Authorship
import com.harnessapk.agent.V2InstallClass
import com.harnessapk.agent.V2_INSTALLATION_PROFILE_ORDER
import com.harnessapk.agent.V2PackageType
import com.harnessapk.agent.V2SourceGenre
import com.harnessapk.agent.V2SourceRecord
import com.harnessapk.agent.installPlanContractError
import kotlinx.coroutines.CancellationException
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
    val availablePackageIds: List<String>,
)

internal sealed interface AgentInstallationDecision {
    data class InstallDirectly(val profileId: String) : AgentInstallationDecision

    data class ShowAdjustment(
        val selectedProfileId: String,
        val suggestedProfileId: String,
        val reason: String,
    ) : AgentInstallationDecision

    data class BlockMissingRequired(val missingPackageIds: List<String>) : AgentInstallationDecision

    data class BlockUnavailableProfile(
        val profileId: String,
        val missingPackageIds: List<String>,
    ) : AgentInstallationDecision
}

internal enum class AgentPackageKind {
    V2_BUNDLE,
    STANDALONE,
}

internal data class AgentStorageFailure(
    val sessionId: String,
    val failedProfileId: String,
    val packageKind: AgentPackageKind,
    val requiredBytes: Long,
    val availableBytes: Long,
)

internal sealed interface AgentPackageInstallAttempt {
    data class Success(val result: AgentInstallResult) : AgentPackageInstallAttempt

    data class Failure(
        val message: String,
        val storageFailure: AgentStorageFailure? = null,
        val sessionRetained: Boolean = storageFailure != null,
    ) : AgentPackageInstallAttempt
}

internal suspend fun attemptAgentPackageInstall(
    sessionId: String,
    profileId: String,
    packageKind: AgentPackageKind,
    install: suspend () -> AgentInstallResult,
    refreshAvailableBytes: () -> Long,
): AgentPackageInstallAttempt = try {
    AgentPackageInstallAttempt.Success(install())
} catch (error: CancellationException) {
    throw error
} catch (error: AgentInsufficientStorageException) {
    val refreshed = try {
        refreshAvailableBytes()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        error.availableBytes
    }
    val available = refreshed.takeIf { it >= 0L } ?: error.availableBytes
    val action = if (packageKind == AgentPackageKind.V2_BUNDLE) {
        "释放空间后重试，或调整资料。"
    } else {
        "释放空间后重试。"
    }
    val message = if (error.requiredBytes >= 0L && available >= 0L) {
        "安装空间不足：需要 ${error.requiredBytes} 字节，可用 $available 字节。$action"
    } else if (available >= 0L) {
        "安装空间在数据库写入期间耗尽，可用 $available 字节。$action"
    } else {
        "安装空间不足。$action"
    }
    AgentPackageInstallAttempt.Failure(
        message = message,
        storageFailure = AgentStorageFailure(
            sessionId = sessionId,
            failedProfileId = profileId,
            packageKind = packageKind,
            requiredBytes = error.requiredBytes,
            availableBytes = available,
        ),
    )
} catch (error: Throwable) {
    AgentPackageInstallAttempt.Failure(error.message ?: "智能体安装失败")
}

internal fun shouldAutoRefreshStorageFailure(
    storageFailure: AgentStorageFailure,
    sessionId: String,
): Boolean =
    storageFailure.sessionId == sessionId &&
        storageFailure.packageKind == AgentPackageKind.V2_BUNDLE &&
        storageFailure.requiredBytes >= 0L

internal fun clearRecoveredStorageFailure(
    failure: AgentPackageInstallAttempt.Failure?,
    availableBytes: Long,
): AgentPackageInstallAttempt.Failure? {
    val storage = failure?.storageFailure ?: return failure
    return failure.takeUnless {
        storage.requiredBytes >= 0L && availableBytes >= storage.requiredBytes
    }
}

internal fun visibleInstallFailure(
    failure: AgentPackageInstallAttempt.Failure?,
    sessionId: String,
    selectedProfileId: String,
): AgentPackageInstallAttempt.Failure? {
    val storage = failure?.storageFailure ?: return failure
    return failure.takeIf {
        storage.sessionId == sessionId && storage.failedProfileId == selectedProfileId
    }
}

internal fun installationDecision(
    plan: AgentInstallationPlan,
    availableBytes: Long,
    requestedProfileId: String?,
    storageFailure: AgentStorageFailure? = null,
    sessionId: String? = null,
): AgentInstallationDecision {
    val blocked = AgentInstallationDecision.BlockMissingRequired(plan.requiredPackageIds.distinct().sorted())
    if (availableBytes < 0L) return blocked
    val validated = validateInstallationPlan(plan) ?: return blocked

    val selectedProfileId = requestedProfileId ?: DEFAULT_INSTALLATION_PROFILE_ID
    val selectedIndex = INSTALLATION_PROFILE_ORDER.indexOf(selectedProfileId)
    if (selectedIndex < 0) return blocked
    val unavailableRequired = plan.requiredPackageIds.filterNot(validated.availablePackageIds::contains).sorted()
    if (unavailableRequired.isNotEmpty()) {
        return AgentInstallationDecision.BlockMissingRequired(unavailableRequired)
    }
    val missingSelected = validated.profiles.getValue(selectedProfileId).packageIds
        .filterNot(validated.availablePackageIds::contains)
        .sorted()
    if (missingSelected.isNotEmpty()) {
        return AgentInstallationDecision.BlockUnavailableProfile(selectedProfileId, missingSelected)
    }
    val signedSelectedBytes = validated.exactBytes(selectedProfileId) ?: return blocked
    val selectedBytes = storageFailure
        ?.takeIf {
            it.packageKind == AgentPackageKind.V2_BUNDLE &&
                it.sessionId == sessionId &&
                it.failedProfileId == selectedProfileId &&
                it.requiredBytes >= 0L &&
                availableBytes < it.requiredBytes
        }
        ?.requiredBytes
        ?.coerceAtLeast(signedSelectedBytes)
        ?: signedSelectedBytes
    if (selectedBytes <= availableBytes) {
        return AgentInstallationDecision.InstallDirectly(selectedProfileId)
    }

    val suggested = INSTALLATION_PROFILE_ORDER
        .take(selectedIndex)
        .asReversed()
        .firstOrNull { profileId ->
            validated.profiles.getValue(profileId).packageIds.all(validated.availablePackageIds::contains) &&
                validated.exactBytes(profileId)?.let { it <= availableBytes } == true
        }
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
    validateInstallationPlan(plan)?.exactBytes(profileId)

internal fun profileAvailableInBundle(plan: AgentInstallationPlan, profileId: String): Boolean {
    val validated = validateInstallationPlan(plan) ?: return false
    return validated.profiles[profileId]?.packageIds?.all(validated.availablePackageIds::contains) == true
}

private data class ValidatedInstallationPlan(
    val agentSizeBytes: Long,
    val packages: Map<String, AgentInstallationPackage>,
    val profiles: Map<String, AgentInstallationProfile>,
    val availablePackageIds: Set<String>,
) {
    fun exactBytes(profileId: String): Long? {
        val profile = profiles[profileId] ?: return null
        return try {
            profile.packageIds.fold(agentSizeBytes) { subtotal, packageId ->
                Math.addExact(subtotal, packages.getValue(packageId).sizeBytes)
            }
        } catch (_: ArithmeticException) {
            null
        }
    }
}

private fun validateInstallationPlan(plan: AgentInstallationPlan): ValidatedInstallationPlan? {
    if (plan.agentPackageId.isBlank() || plan.agentSizeBytes <= 0L) return null
    if (plan.packages.any { it.id.isBlank() || it.sizeBytes <= 0L }) return null
    if (
        installPlanContractError(
            packages = plan.packages.map { declaration ->
                InstallContractPackage(declaration.id, declaration.type, declaration.installClass)
            },
            profiles = plan.profiles.map { profile ->
                InstallContractProfile(profile.id, profile.packageIds)
            },
            requiredCorpusIds = plan.requiredPackageIds,
        ) != null
    ) {
        return null
    }
    val packages = plan.packages.associateBy(AgentInstallationPackage::id)
    if (plan.availablePackageIds.distinct().size != plan.availablePackageIds.size ||
        plan.availablePackageIds.any { it !in packages }
    ) {
        return null
    }
    val profiles = plan.profiles.associateBy(AgentInstallationProfile::id)
    val validated = ValidatedInstallationPlan(
        agentSizeBytes = plan.agentSizeBytes,
        packages = packages,
        profiles = profiles,
        availablePackageIds = plan.availablePackageIds.toSet(),
    )
    if (profiles.keys.any { profileId -> validated.exactBytes(profileId) == null }) return null
    return validated
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

internal val INSTALLATION_PROFILE_ORDER = V2_INSTALLATION_PROFILE_ORDER
internal const val DEFAULT_INSTALLATION_PROFILE_ID = "balanced"

private val CONVERSATIONAL_SOURCE_GENRES = setOf(
    V2SourceGenre.SPEECH,
    V2SourceGenre.CONVERSATION,
    V2SourceGenre.LETTER,
    V2SourceGenre.INTERVIEW,
)
private val DIRECT_SOURCE_AUTHORSHIP = setOf(V2Authorship.DIRECT, V2Authorship.EDITED_DIRECT)
