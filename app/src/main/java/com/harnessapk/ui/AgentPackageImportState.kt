package com.harnessapk.ui

internal data class AgentPackageImportState(
    val sourceProjectId: String? = null,
    val consumedExternalBundleUri: String? = null,
)

internal data class AgentPackageImportTransition(
    val state: AgentPackageImportState,
    val navigateToPackages: Boolean = false,
)

internal sealed interface AgentPackageImportEvent {
    data class ExternalBundleReceived(
        val uri: String,
        val mainMode: MainMode,
        val currentProjectId: String?,
    ) : AgentPackageImportEvent

    data object ExternalBundleConsumed : AgentPackageImportEvent
    data object SettingsOpened : AgentPackageImportEvent
    data object StartConversation : AgentPackageImportEvent
    data object Done : AgentPackageImportEvent
    data class RouteChanged(val isAgentPackagesRoute: Boolean) : AgentPackageImportEvent
}

internal fun reduceAgentPackageImport(
    state: AgentPackageImportState,
    event: AgentPackageImportEvent,
): AgentPackageImportTransition = when (event) {
    is AgentPackageImportEvent.ExternalBundleReceived -> {
        if (event.uri == state.consumedExternalBundleUri) {
            AgentPackageImportTransition(state)
        } else {
            AgentPackageImportTransition(
                state = state.copy(
                    sourceProjectId = event.currentProjectId.takeIf { event.mainMode == MainMode.PROJECT },
                    consumedExternalBundleUri = event.uri,
                ),
                navigateToPackages = true,
            )
        }
    }
    AgentPackageImportEvent.ExternalBundleConsumed -> AgentPackageImportTransition(
        state.copy(consumedExternalBundleUri = null),
    )
    AgentPackageImportEvent.SettingsOpened,
    AgentPackageImportEvent.StartConversation,
    AgentPackageImportEvent.Done,
    -> AgentPackageImportTransition(state.copy(sourceProjectId = null))
    is AgentPackageImportEvent.RouteChanged -> AgentPackageImportTransition(
        if (event.isAgentPackagesRoute) state else state.copy(sourceProjectId = null),
    )
}
