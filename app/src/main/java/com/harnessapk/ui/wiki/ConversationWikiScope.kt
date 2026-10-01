package com.harnessapk.ui.wiki

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.harnessapk.common.AppContainer
import com.harnessapk.storage.WikiEntity
import com.harnessapk.wiki.ConversationWikiMount
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiVersionState
import kotlinx.coroutines.CoroutineScope

/**
 * 会话知识库范围的会话内状态机：挂载列表、已装目录、写入控制器与 picker 可见性。
 * 自 ChatScreen 收敛到此；聊天侧只读标签/计数/启用引用并转发用户操作。
 */
@Stable
class ConversationWikiScope internal constructor(
    private val container: AppContainer,
    private val conversationId: String,
    private val scope: CoroutineScope,
) {
    var pickerVisible by mutableStateOf(false)
        private set

    private var mounts by mutableStateOf(emptyList<ConversationWikiMount>())
    private var catalog by mutableStateOf(emptyList<ConversationWikiCatalogEntry>())

    internal val controller = ConversationWikiController(
        scope = scope,
        applyScope = { selections ->
            container.conversationWikiRepository.replaceMountScope(conversationId, selections)
        },
        restoreDefaultsAction = {
            container.conversationWikiRepository.restoreDefaults(conversationId)
        },
        reloadMounts = {
            container.conversationWikiRepository.mounts(conversationId)
        },
    )

    val uiState: ConversationWikiUiState
        get() = conversationWikiUiState(mounts, catalog)

    val toolbarLabel: String
        get() = uiState.toolbarLabel

    val enabledReadyCount: Int
        get() = uiState.options.count { it.enabled && !it.unavailable }

    /** 当前启用挂载的引用：新会话延续范围等场景直接取用。 */
    val enabledRefs: List<WikiRef>
        get() = mounts.filter { it.enabled }.map { it.ref }

    fun canApply(): Boolean = controller.canApply()

    fun openPicker() {
        pickerVisible = true
    }

    fun closePicker() {
        pickerVisible = false
    }

    /** 按「已装 Wiki × 版本就绪态」重建目录，并拉取本会话挂载。 */
    suspend fun reload(installed: List<WikiEntity>, onError: (Throwable) -> Unit) {
        runCatching {
            val freshCatalog = installed.map { wiki ->
                ConversationWikiCatalogEntry(
                    wikiId = wiki.id,
                    title = wiki.title,
                    versions = container.wikiRepository.listVersions(wiki.id).map { version ->
                        ConversationWikiCatalogVersion(
                            ref = WikiRef(version.wikiId, version.version),
                            ready = version.state == WikiVersionState.READY.name,
                            active = wiki.activeVersion == version.version,
                        )
                    },
                )
            }
            container.conversationWikiRepository.mounts(conversationId) to freshCatalog
        }.onSuccess { (freshMounts, freshCatalog) ->
            mounts = freshMounts
            catalog = freshCatalog
        }.onFailure(onError)
    }

    /** 写操作落定回执：刷新挂载并收起 picker。 */
    internal fun acceptSettled(state: ConversationWikiControllerState) {
        state.refreshedMounts?.let { refreshed ->
            mounts = refreshed
            pickerVisible = false
        }
    }
}

@Composable
internal fun rememberConversationWikiScope(
    container: AppContainer,
    conversationId: String,
    onError: (Throwable) -> Unit,
): ConversationWikiScope {
    val scope = rememberCoroutineScope()
    val wikiScope = remember(conversationId) { ConversationWikiScope(container, conversationId, scope) }
    val installedWikis by container.wikiRepository.observeWikis().collectAsState(initial = emptyList())
    LaunchedEffect(conversationId, installedWikis) {
        wikiScope.reload(installedWikis, onError)
    }
    val controllerState by wikiScope.controller.state.collectAsState()
    LaunchedEffect(controllerState.settledGeneration) {
        wikiScope.acceptSettled(controllerState)
    }
    return wikiScope
}
