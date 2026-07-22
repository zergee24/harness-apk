package com.harnessapk.ui

internal data class WikiPackageImportState(
    val pendingUri: String? = null,
)

internal data class WikiPackageImportTransition(
    val state: WikiPackageImportState,
    val navigateToLibrary: Boolean = false,
    val errorMessage: String? = null,
)

internal sealed interface WikiPackageImportEvent {
    data class ExternalPackageReceived(val uri: String) : WikiPackageImportEvent
    data class PickerPackageSelected(val uri: String) : WikiPackageImportEvent
    data object RestorePendingImport : WikiPackageImportEvent
    data object ImportCancelled : WikiPackageImportEvent
    data class ImportRejected(val message: String) : WikiPackageImportEvent
    data object ImportCompleted : WikiPackageImportEvent
}

internal enum class WikiImportTrustDecision {
    REQUIRE_CONFIRMATION,
    INSTALL_DIRECTLY,
}

internal fun wikiImportTrustDecision(isKnownPublisher: Boolean): WikiImportTrustDecision =
    if (isKnownPublisher) WikiImportTrustDecision.INSTALL_DIRECTLY else WikiImportTrustDecision.REQUIRE_CONFIRMATION

internal fun reduceWikiPackageImport(
    state: WikiPackageImportState,
    event: WikiPackageImportEvent,
): WikiPackageImportTransition = when (event) {
    is WikiPackageImportEvent.ExternalPackageReceived -> state.withPendingUri(event.uri)
    is WikiPackageImportEvent.PickerPackageSelected -> state.withPendingUri(event.uri)
    WikiPackageImportEvent.RestorePendingImport -> WikiPackageImportTransition(
        state = state,
        navigateToLibrary = state.pendingUri != null,
    )
    WikiPackageImportEvent.ImportCancelled,
    WikiPackageImportEvent.ImportCompleted,
    -> WikiPackageImportTransition(state.copy(pendingUri = null))
    is WikiPackageImportEvent.ImportRejected -> WikiPackageImportTransition(
        state = state.copy(pendingUri = null),
        errorMessage = event.message,
    )
}

private fun WikiPackageImportState.withPendingUri(uri: String): WikiPackageImportTransition {
    require(uri.isNotBlank()) { "知识库导入地址不能为空" }
    if (uri == pendingUri) return WikiPackageImportTransition(this)
    return WikiPackageImportTransition(
        state = copy(pendingUri = uri),
        navigateToLibrary = true,
    )
}
