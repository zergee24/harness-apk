package com.harnessapk.session

import com.harnessapk.projectsearch.ProjectSourceAuthority
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class ContextSection { PROJECT_GOALS, KEY_DECISIONS, CURRENT_STATUS, FOLLOW_UP }
enum class FactOperation { UPSERT }

data class ContextFactEvidence(
    val id: String,
    val authority: ProjectSourceAuthority,
    val sourceSha256: String,
)

data class ContextFactCandidate(
    val section: ContextSection,
    val statement: String,
    val evidenceIds: List<String>,
    val evidenceAuthorities: Set<ProjectSourceAuthority>,
    val operation: FactOperation,
    val semanticKey: String,
    val evidenceHash: String,
)

data class ContextFactDecision(val accepted: Boolean, val reason: String)

class ContextFactPolicy {
    fun accept(
        candidates: List<ContextFactCandidate>,
        allowedEvidence: List<ContextFactEvidence>,
        suppressedSemanticKeys: Set<String> = emptySet(),
    ): List<ContextFactCandidate> {
        val evidenceById = allowedEvidence
            .groupBy { it.id.trim() }
            .mapNotNull { (id, matches) ->
                val evidence = matches.singleOrNull()
                if (id.isBlank() || evidence == null || !evidence.sourceSha256.matches(SHA256)) {
                    null
                } else {
                    id to evidence.copy(id = id, sourceSha256 = evidence.sourceSha256.lowercase(Locale.ROOT))
                }
            }
            .toMap()
        return candidates.mapNotNull { candidate ->
            val evidenceIds = candidate.evidenceIds.map(String::trim).filter(String::isNotBlank).distinct().sorted()
            if (evidenceIds.isEmpty()) return@mapNotNull null
            val evidence = evidenceIds.mapNotNull(evidenceById::get)
            if (evidence.size != evidenceIds.size) return@mapNotNull null
            val evidenceHash = evidenceHash(evidence)
            val enriched = candidate.copy(
                statement = candidate.statement.trim().replace(WHITESPACE, " "),
                evidenceIds = evidenceIds,
                evidenceAuthorities = evidence.mapTo(linkedSetOf(), ContextFactEvidence::authority),
                semanticKey = semanticKey(candidate.section, candidate.statement, evidenceHash),
                evidenceHash = evidenceHash,
            )
            enriched.takeIf { evaluate(it, suppressedSemanticKeys).accepted }
        }.distinctBy(ContextFactCandidate::semanticKey)
    }

    fun evaluate(
        candidate: ContextFactCandidate,
        suppressedSemanticKeys: Set<String> = emptySet(),
    ): ContextFactDecision = when {
        candidate.statement.isBlank() -> ContextFactDecision(false, "事实陈述为空")
        candidate.evidenceIds.isEmpty() -> ContextFactDecision(false, "缺少项目证据")
        candidate.semanticKey in suppressedSemanticKeys -> ContextFactDecision(false, "相同事实已处理")
        candidate.section in AUTHORITATIVE_SECTIONS &&
            candidate.evidenceAuthorities.all { it == ProjectSourceAuthority.ASSISTANT_PROPOSAL } ->
            ContextFactDecision(false, "助手提案不能证明项目决策或状态")
        else -> ContextFactDecision(true, "允许进入审核")
    }

    private companion object {
        val AUTHORITATIVE_SECTIONS = setOf(ContextSection.KEY_DECISIONS, ContextSection.CURRENT_STATUS)
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        val WHITESPACE = Regex("\\s+")

        fun evidenceHash(evidence: List<ContextFactEvidence>): String = evidence
            .map { it.sourceSha256.lowercase(Locale.ROOT) }
            .distinct()
            .sorted()
            .joinToString("\n")
            .sha256()

        fun semanticKey(section: ContextSection, statement: String, evidenceHash: String): String {
            val normalizedStatement = Normalizer.normalize(statement.trim(), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(WHITESPACE, " ")
            val digest = "${section.name}\u001f$normalizedStatement\u001f$evidenceHash".sha256()
            return "${section.name.lowercase(Locale.ROOT)}:$digest"
        }

        fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
