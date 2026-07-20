package com.harnessapk.agent

internal data class InstallContractPackage(
    val id: String,
    val type: V2PackageType,
    val installClass: V2InstallClass,
)

internal data class InstallContractProfile(
    val id: String,
    val packageIds: List<String>,
)

internal val V2_INSTALLATION_PROFILE_ORDER = listOf("lite", "balanced", "complete", "source")

internal fun installPlanContractError(
    packages: List<InstallContractPackage>,
    profiles: List<InstallContractProfile>,
    requiredCorpusIds: List<String>,
): String? {
    if (packages.any { it.id.isBlank() } || packages.map(InstallContractPackage::id).distinct().size != packages.size) {
        return "install plan package ID 重复或为空"
    }
    if (requiredCorpusIds.distinct().size != requiredCorpusIds.size) {
        return "install plan required corpus ID 重复"
    }
    packages.forEach { declaration ->
        val validType = when (declaration.installClass) {
            V2InstallClass.REQUIRED,
            V2InstallClass.RECOMMENDED,
            V2InstallClass.OPTIONAL,
            -> declaration.type == V2PackageType.CORPUS
            V2InstallClass.SOURCE -> declaration.type == V2PackageType.SOURCE
        }
        if (!validType) return "install plan package type/installClass 不匹配：${declaration.id}"
    }

    val packageById = packages.associateBy(InstallContractPackage::id)
    val requiredIds = packages
        .filter { it.installClass == V2InstallClass.REQUIRED }
        .mapTo(linkedSetOf(), InstallContractPackage::id)
    if (requiredCorpusIds.toSet() != requiredIds) {
        return "install plan required corpus 集合与 required package 声明不一致"
    }
    if (profiles.map(InstallContractProfile::id).distinct().size != profiles.size ||
        profiles.mapTo(linkedSetOf(), InstallContractProfile::id) != V2_INSTALLATION_PROFILE_ORDER.toSet()
    ) {
        return "install plan 必须且只能声明 lite/balanced/complete/source"
    }
    val profileById = profiles.associateBy(InstallContractProfile::id)
    profiles.forEach { profile ->
        if (profile.packageIds.distinct().size != profile.packageIds.size) {
            return "install plan profile ${profile.id} package ID 重复"
        }
        val unknown = profile.packageIds.firstOrNull { it !in packageById }
        if (unknown != null) return "install plan profile ${profile.id} 引用了未知 package：$unknown"
    }

    val recommendedIds = packages
        .filter { it.installClass == V2InstallClass.RECOMMENDED }
        .mapTo(linkedSetOf(), InstallContractPackage::id)
    val optionalIds = packages
        .filter { it.installClass == V2InstallClass.OPTIONAL }
        .mapTo(linkedSetOf(), InstallContractPackage::id)
    val sourceIds = packages
        .filter { it.installClass == V2InstallClass.SOURCE }
        .mapTo(linkedSetOf(), InstallContractPackage::id)
    val expected = linkedMapOf(
        "lite" to requiredIds,
        "balanced" to (requiredIds + recommendedIds),
        "complete" to (requiredIds + recommendedIds + optionalIds),
        "source" to (requiredIds + recommendedIds + optionalIds + sourceIds),
    )
    expected.forEach { (profileId, expectedIds) ->
        if (profileById.getValue(profileId).packageIds.toSet() != expectedIds) {
            return "install plan profile $profileId 的 installClass 成员无效"
        }
    }
    V2_INSTALLATION_PROFILE_ORDER.zipWithNext().forEach { (lower, higher) ->
        val lowerIds = profileById.getValue(lower).packageIds.toSet()
        val higherIds = profileById.getValue(higher).packageIds.toSet()
        if (!higherIds.containsAll(lowerIds)) {
            return "install plan profile 不满足单调包含关系：$lower ⊄ $higher"
        }
    }
    return null
}
