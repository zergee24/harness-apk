package com.harnessapk.ui.workbrief

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harnessapk.common.AppContainer
import com.harnessapk.workbrief.CaptureSessionStatus
import com.harnessapk.workbrief.UserMarkerType
import com.harnessapk.workbrief.WorkBriefRepository
import com.harnessapk.workbrief.capture.BriefCaptureController
import com.harnessapk.workbrief.capture.BriefInkView
import com.harnessapk.workbrief.journal.StrokeJournal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 简报记录页（P1）：有限画布 + 4 型标记 + 多页 + 暂停/恢复/结束。 */
@Composable
fun WorkBriefCaptureScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    briefId: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<BriefCaptureController?>(null) }
    var paused by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("准备中") }
    var markerDialog by remember { mutableStateOf<UserMarkerType?>(null) }
    var markerNote by remember { mutableStateOf("") }
    var pageChips by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var currentPageId by remember { mutableStateOf<String?>(null) }
    var eraserMode by remember { mutableStateOf(false) }
    var fingerMode by remember { mutableStateOf(false) }
    var inkView by remember { mutableStateOf<BriefInkView?>(null) }
    var sealed by remember { mutableStateOf(false) }
    var replaying by remember { mutableStateOf(false) }

    fun refreshPages() {
        controller?.let { c ->
            pageChips = c.pages.values.sortedBy { it.pageIndex }.map { it.pageId to it.pageIndex }
            currentPageId = c.currentPageId
        }
    }

    LaunchedEffect(briefId) {
        val instance = BriefCaptureController(
            context = context,
            db = container.database,
            repository = WorkBriefRepository(container.database),
            scope = scope,
            briefId = briefId,
        )
        instance.onInkChanged = { l, t, r, b -> inkView?.postInvalidate(l, t, r, b) }
        instance.onError = { message -> statusText = message }
        instance.prepare()
        controller = instance
        refreshPages()
        if (instance.sessionStatus == CaptureSessionStatus.SEALED.name) {
            sealed = true
            inkView?.inputEnabled = false
            statusText = "已封存 · 可回放"
        } else {
            statusText = "记录中"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "工作简报 · $statusText",
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                enabled = controller != null && !paused,
                onClick = {
                    controller?.pause { paused = true; statusText = "已暂停" }
                },
            ) { Text("暂停") }
            if (sealed) {
                OutlinedButton(
                    enabled = !replaying,
                    onClick = {
                        val c = controller ?: return@OutlinedButton
                        replaying = true
                        scope.launch {
                            runCatching {
                                c.resetInkForReplay()
                                statusText = "回放中"
                                for (record in c.replayRecords) {
                                    val obj = org.json.JSONObject(String(record.payload, Charsets.UTF_8))
                                    val pageId = obj.optString("pageId")
                                    when (record.type) {
                                        StrokeJournal.TYPE_PAGE_ADDED -> {
                                            c.replaySwitchPage(pageId)
                                            delay(150)
                                        }
                                        StrokeJournal.TYPE_ERASE_POINT -> {
                                            c.replayErase(pageId, obj.optDouble("x"), obj.optDouble("y"))
                                            delay(120)
                                        }
                                        StrokeJournal.TYPE_STROKE_COMMITTED -> {
                                            c.replaySwitchPage(pageId)
                                            val pts = obj.optJSONArray("pts") ?: continue
                                            var prevT = 0L
                                            val tool = obj.optString("tool", "stylus")
                                            for (i in 0 until pts.length()) {
                                                val arr = pts.optJSONArray(i) ?: continue
                                                val t = arr.optLong(2)
                                                val gap = (t - prevT).coerceIn(8L, 300L).toInt()
                                                c.replayAppend(
                                                    pageId, tool,
                                                    arr.optDouble(0), arr.optDouble(1),
                                                    t, arr.optDouble(3).toFloat(),
                                                )
                                                prevT = t
                                                delay(gap.toLong())
                                            }
                                        }
                                    }
                                    delay(200)
                                }
                            }
                            statusText = "回放结束"
                            replaying = false
                        }
                    },
                ) { Text(if (statusText == "回放结束") "重播" else "回放") }
            } else {
                OutlinedButton(
                    enabled = controller != null && paused,
                    onClick = {
                        controller?.resume { paused = false; statusText = "记录中" }
                    },
                ) { Text("继续") }
            }
            Button(
                enabled = controller != null,
                onClick = {
                    controller?.stop {
                        paused = false
                        sealed = true
                        inkView?.inputEnabled = false
                        statusText = "已封存 · 可回放"
                    }
                },
            ) { Text("结束") }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    var created: BriefInkView? = null
                    BriefInkView(
                        ctx,
                        { controller },
                        { l, t, r, b -> created?.postInvalidate(l, t, r, b) },
                    ).also { created = it }
                },
            )
        }

        if (!sealed) Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserMarkerType.entries.forEach { type ->
                FilterChip(
                    selected = false,
                    onClick = {
                        markerDialog = type
                        markerNote = ""
                    },
                    label = {
                        Text(
                            when (type) {
                                UserMarkerType.DECISION -> "决策"
                                UserMarkerType.QUESTION -> "疑问"
                                UserMarkerType.TODO -> "待办"
                                UserMarkerType.BOOKMARK -> "书签"
                            },
                        )
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    eraserMode = !eraserMode
                    controller?.eraserMode = eraserMode
                },
            ) { Text(if (eraserMode) "橡皮：开" else "橡皮：关") }
            OutlinedButton(
                onClick = {
                    fingerMode = !fingerMode
                    inkView?.fingerMode = fingerMode
                },
            ) { Text(if (fingerMode) "手指：开" else "手指：关") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pageChips.forEach { (pageId, index) ->
                FilterChip(
                    selected = pageId == currentPageId,
                    onClick = {
                        controller?.switchPage(pageId)
                        currentPageId = pageId
                        inkView?.invalidate()
                    },
                    label = { Text("第 ${index + 1} 页") },
                )
            }
            OutlinedButton(onClick = {
                scope.launch {
                    controller?.addPage()
                    refreshPages()
                }
            }) { Text("新增页") }
        }
    }

    markerDialog?.let { type ->
        AlertDialog(
            onDismissRequest = { markerDialog = null },
            title = { Text("添加标记：${type.label()}") },
            text = {
                OutlinedTextField(
                    value = markerNote,
                    onValueChange = { markerNote = it },
                    label = { Text("备注（可空）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = markerNote.isNotBlank(),
                    onClick = {
                        val note = markerNote
                        markerDialog = null
                        controller?.addMarker(type, note) {
                            statusText = "已添加标记：${type.label()}"
                        }
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                OutlinedButton(onClick = { markerDialog = null }) { Text("取消") }
            },
        )
    }
}

private fun UserMarkerType.label(): String = when (this) {
    UserMarkerType.DECISION -> "决策"
    UserMarkerType.QUESTION -> "疑问"
    UserMarkerType.TODO -> "待办"
    UserMarkerType.BOOKMARK -> "书签"
}
