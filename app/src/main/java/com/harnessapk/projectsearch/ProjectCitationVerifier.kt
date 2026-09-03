package com.harnessapk.projectsearch

object ProjectCitationVerifier {
    private val tokenPattern = Regex("⟦P[1-9][0-9]*⟧")

    fun verify(text: String, allowedTokens: Set<String>): ProjectCitationVerification {
        val seen = tokenPattern.findAll(text).map { it.value }.toList()
        val valid = seen.filter { it in allowedTokens }.distinct()
        val unknown = seen.filterNot { it in allowedTokens }.distinct()
        val sanitized = tokenPattern.replace(text) { match ->
            match.value.takeIf { it in allowedTokens }.orEmpty()
        }.replace(Regex("[  ]+([，。；：！？])"), "$1")
        return ProjectCitationVerification(sanitized, valid, unknown)
    }
}
