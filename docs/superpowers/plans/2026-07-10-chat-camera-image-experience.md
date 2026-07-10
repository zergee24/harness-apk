# 会话拍照与图片体验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持单张拍照或相册选图，持久保存会话图片，在消息内渲染缩略图，并提供全屏预览与用户主动保存图片能力。

**Architecture:** 图片在进入待发送状态时即复制到应用专属 `filesDir/chat-images/`，`ChatAttachment.uri` 继续保存该本地 `content://` URI，因此无需修改现有 Room 表。输入区只维护一张 `PendingImageAttachment`；相册和相机最终回填同一状态。消息行通过附件 Flow 和 `UiMessagePartType.IMAGE` 渲染图片，预览层统一处理本地 URI、data URI 和 HTTPS 图片来源，并由图片存储服务负责缓存及写入系统相册。

**Tech Stack:** Kotlin、Jetpack Compose、AndroidX Activity Result、FileProvider、MediaStore、Coroutines、Room、JUnit 4、Compose UI Test、Android instrumentation test。

## Global Constraints

- 一条待发送消息只支持一张图片；不实现连拍、多选、裁剪、滤镜或标注。
- 拍照、发送和预览不得自动写入系统相册；只有“保存图片”动作写入媒体库。
- 拍照和相册图片必须复制到应用持久目录，不能只保存临时 Photo Picker URI。
- 保持既有 8 MB 发送上限、图片压缩和模型图片输入能力校验。
- 只在用户点击“拍照”时请求 `CAMERA` 运行时权限；权限拒绝不能清空已输入文本。
- 模型图片仅接受 `content://`、`file://`、`data:image/*` 和 HTTPS 来源；HTTP 与其他 scheme 显示失败占位。
- 不新增第三方图片加载或网络依赖；沿用项目现有 HTTP 与协程基础设施。
- 工作台顶部维持标签页优先的 A 方案，本计划不修改项目工作台。
- 使用中文提交信息，只暂存当前任务列出的文件，不自动推送。

---

## File Structure

- `app/src/main/java/com/harnessapk/chat/ChatImageStore.kt`: 管理图片复制、相机输出 URI、data/HTTPS 解析、本地缓存和 MediaStore 保存。
- `app/src/main/java/com/harnessapk/chat/ChatRepository.kt`: 暴露每条消息的附件 Flow，继续使用现有 `message_attachments` 表。
- `app/src/main/java/com/harnessapk/common/AppContainer.kt`: 创建并注入 `ChatImageStore`。
- `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`: 在插入消息前把待发送附件标准化为持久 URI。
- `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`: 相机权限、操作面板、图片消息数据接线和全屏预览状态。
- `app/src/main/java/com/harnessapk/ui/chat/ChatImageComponents.kt`: 缩略图、图片消息部件、全屏预览和保存反馈。
- `app/src/main/AndroidManifest.xml`: 声明相机权限。
- `app/src/main/res/xml/apk_file_paths.xml`: 增加受限的聊天图片 FileProvider 路径。
- `app/src/test/java/com/harnessapk/chat/ChatImageStoreTest.kt`: 持久复制、来源校验、data URI 和远程缓存逻辑。
- `app/src/test/java/com/harnessapk/chat/ChatRepositoryTest.kt`: 消息附件观察和图片部件映射。
- `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt`: 输入区动作、权限结果和图片来源选择逻辑。
- `app/src/androidTest/java/com/harnessapk/ui/chat/ChatImageComponentsTest.kt`: 消息缩略图、预览、保存动作与错误状态。

---

### Task 1: 持久图片资产与附件查询

**Files:**
- Create: `app/src/main/java/com/harnessapk/chat/ChatImageStore.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/ChatRepository.kt:20-45,120-155`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt:20-90`
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt:35-105`
- Create: `app/src/test/java/com/harnessapk/chat/ChatImageStoreTest.kt`
- Modify: `app/src/test/java/com/harnessapk/chat/ChatRepositoryTest.kt`

**Interfaces:**
- Consumes: `PendingImageAttachment`, `ChatAttachment`, `MessageAttachmentDao`。
- Produces: `ChatImageStore.persist`, `ChatImageStore.createCameraUri`, `ChatImageStore.saveToMediaStore`, `ChatRepository.observeAttachments`。

- [ ] **Step 1: 写入失败测试**

在 `ChatImageStoreTest.kt` 添加：

```kotlin
@Test
fun persistCopiesPickerImageIntoChatImagesDirectory() = runTest {
    val source = temporaryImageUri("picker.jpg", "picker-bytes")

    val persisted = store.persist(source, "image/jpeg")

    assertTrue(persisted.uri.toString().contains(".fileprovider"))
    assertEquals("image/jpeg", persisted.mimeType)
    assertEquals("picker-bytes", readPersistedText(persisted.uri))
}

@Test
fun resolveRejectsHttpImageUrl() = runTest {
    val result = store.resolveDisplaySource("http://example.com/image.jpg", "image/jpeg")

    assertTrue(result is ChatImageSource.Invalid)
}
```

- [ ] **Step 2: 运行 RED 测试**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatImageStoreTest
```

Expected: 编译失败，`ChatImageStore`、`ChatImageSource` 与持久图片 API 尚不存在。

- [ ] **Step 3: 实现受控图片存储**

创建 `ChatImageStore.kt`，公开如下边界：

```kotlin
data class PersistedChatImage(
    val uri: Uri,
    val mimeType: String,
)

sealed interface ChatImageSource {
    data class Local(val uri: Uri, val mimeType: String) : ChatImageSource
    data class Data(val bytes: ByteArray, val mimeType: String) : ChatImageSource
    data class Remote(val httpsUrl: String, val mimeType: String?) : ChatImageSource
    data class Invalid(val reason: String) : ChatImageSource
}

class ChatImageStore(
    private val context: Context,
    private val httpClient: HttpClient,
    private val dispatchers: AppDispatchers,
) {
    suspend fun persist(source: Uri, mimeType: String): PersistedChatImage
    fun createCameraUri(): Uri
    suspend fun resolveDisplaySource(raw: String, mimeType: String?): ChatImageSource
    suspend fun materialize(source: ChatImageSource): PersistedChatImage
    suspend fun saveToMediaStore(source: Uri, mimeType: String): String
    suspend fun deleteIfManaged(uri: Uri)
}
```

实现规则：

- `persist` 使用 `ContentResolver.openInputStream` 复制到 `filesDir/chat-images/<uuid>.<extension>`，通过 `${applicationId}.fileprovider` 返回受控 URI。
- `createCameraUri` 在同一目录创建空 `.jpg` 文件，供 `TakePicture` 直接写入；调用方取消或失败时调用 `deleteIfManaged`。
- `resolveDisplaySource` 只返回 `content`、`file`、`data:image/` 与 `https`；其余返回 `Invalid`。
- `materialize` 将 data URI 与 HTTPS 内容写入持久目录，HTTPS 下载只接受成功响应和 `image/*` Content-Type，下载失败抛出用户可读错误。
- `saveToMediaStore` 使用 `MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL_PRIMARY)`、`DISPLAY_NAME`、`MIME_TYPE` 和 `IS_PENDING` 写入；成功后返回显示名称。

在 `ChatRepository` 添加：

```kotlin
fun observeAttachments(messageId: String): Flow<List<ChatAttachment>> =
    attachmentDao.observeForMessage(messageId).map { rows -> rows.map { it.toDomain() } }
```

在 `SendMessageUseCase` 中，在 `insertUserMessage` 前执行：

```kotlin
val persistedAttachments = attachments.map { attachment ->
    chatImageStore.persist(attachment.uri, attachment.mimeType)
        .let { PendingImageAttachment(it.uri, it.mimeType) }
}
```

`AppContainer` 创建 `ChatImageStore(appContext, chatHttpClient, dispatchers)` 并传给 `SendMessageUseCase`。

- [ ] **Step 4: 增加附件 Flow 回归测试**

在 `ChatRepositoryTest.kt` 添加：

```kotlin
@Test
fun observeAttachmentsReturnsPersistedImageAttachment() = runTest {
    val messageId = repository.insertUserMessage(
        conversationId = conversationId,
        content = "图片",
        attachments = listOf(PendingImageAttachment(Uri.parse("content://app/image/1"), "image/jpeg")),
    )

    val attachments = repository.observeAttachments(messageId).first()

    assertEquals(listOf("content://app/image/1"), attachments.map { it.uri })
}
```

- [ ] **Step 5: 运行 GREEN 测试**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatImageStoreTest --tests com.harnessapk.chat.ChatRepositoryTest
```

Expected: 所有测试通过；相册 URI 不再作为历史消息唯一来源。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/chat/ChatImageStore.kt app/src/main/java/com/harnessapk/chat/ChatRepository.kt app/src/main/java/com/harnessapk/common/AppContainer.kt app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt app/src/test/java/com/harnessapk/chat/ChatImageStoreTest.kt app/src/test/java/com/harnessapk/chat/ChatRepositoryTest.kt
git commit -m "功能：持久化会话图片附件"
```

---

### Task 2: 统一拍照与相册入口

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:2-6`
- Modify: `app/src/main/res/xml/apk_file_paths.xml:2-5`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt:180-520,2700-2890`
- Modify: `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt`
- Create: `app/src/androidTest/java/com/harnessapk/ui/chat/ChatCameraEntryTest.kt`

**Interfaces:**
- Consumes: `ChatImageStore.createCameraUri`, `ActivityResultContracts.TakePicture`, `PickVisualMedia`。
- Produces: `ChatImageSourceAction`, `shouldRequestCameraPermission`, 单一 `selectedImage` 回填路径。

- [ ] **Step 1: 写入输入动作 RED 测试**

在 `ChatUiStateTest.kt` 添加：

```kotlin
@Test
fun cameraActionRequestsPermissionOnlyWhenNotGranted() {
    assertEquals(ChatImageSourceAction.REQUEST_CAMERA_PERMISSION, cameraAction(permissionGranted = false))
    assertEquals(ChatImageSourceAction.LAUNCH_CAMERA, cameraAction(permissionGranted = true))
}

@Test
fun cancelledCameraKeepsTypedDraft() {
    assertEquals("待发送文字", cameraCancelledText("待发送文字"))
}
```

- [ ] **Step 2: 运行 RED 测试**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
```

Expected: 缺少 `ChatImageSourceAction`、`cameraAction` 与 `cameraCancelledText`。

- [ ] **Step 3: 配置相机能力和操作面板**

在 manifest 增加：

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

在 `apk_file_paths.xml` 增加：

```xml
<files-path name="chat_images" path="chat-images/" />
```

在 `ChatScreen` 定义：

```kotlin
internal enum class ChatImageSourceAction {
    REQUEST_CAMERA_PERMISSION,
    LAUNCH_CAMERA,
}

internal fun cameraAction(permissionGranted: Boolean): ChatImageSourceAction =
    if (permissionGranted) ChatImageSourceAction.LAUNCH_CAMERA
    else ChatImageSourceAction.REQUEST_CAMERA_PERMISSION

internal fun cameraCancelledText(currentText: String): String = currentText
```

增加三条 launcher：`RequestPermission(CAMERA)`、`TakePicture` 与现有 `PickVisualMedia`。拍照前调用 `chatImageStore.createCameraUri()` 并保存 `pendingCameraUri`；`TakePicture` 成功时将 URI 和 `image/jpeg` 写入 `selectedImage`/`selectedMimeType`，失败时删除受控临时文件。

将输入框 `onPickImage` 替换为 `onAddImage`，用 `ModalBottomSheet` 展示两个 56dp 高的操作：

```kotlin
TextButton(onClick = onTakePhoto) { Text("拍照") }
TextButton(onClick = onPickFromAlbum) { Text("从相册选择") }
```

相机权限拒绝时设置：

```kotlin
errorText = "未获得相机权限，可从相册选择图片"
```

取消相机时不修改 `text`、不设置附件、不显示错误。

- [ ] **Step 4: 写入设备入口测试**

在 `ChatCameraEntryTest.kt` 测试操作面板：

```kotlin
composeRule.onNodeWithContentDescription("添加图片").performClick()
composeRule.onNodeWithText("拍照").assertIsDisplayed().assertHasClickAction()
composeRule.onNodeWithText("从相册选择").assertIsDisplayed().assertHasClickAction()
```

使用可注入的 action lambda 断言点击“拍照”只触发相机动作，点击“从相册选择”只触发 picker 动作。

- [ ] **Step 5: 运行 GREEN 测试**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.chat.ChatCameraEntryTest
```

Expected: JVM 与两条 Compose 入口测试通过。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/apk_file_paths.xml app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt app/src/androidTest/java/com/harnessapk/ui/chat/ChatCameraEntryTest.kt
git commit -m "功能：支持会话拍照选图"
```

---

### Task 3: 会话图片缩略图、预览与保存

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/chat/ChatImageComponents.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt:1430-1490,2430-2465`
- Modify: `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt`
- Create: `app/src/androidTest/java/com/harnessapk/ui/chat/ChatImageComponentsTest.kt`

**Interfaces:**
- Consumes: `ChatAttachment`, `ChatImageStore.resolveDisplaySource`, `ChatImageStore.materialize`, `ChatImageStore.saveToMediaStore`。
- Produces: `ChatImageThumbnail`, `ChatImagePreviewDialog`, `imagePartSource`。

- [ ] **Step 1: 写入 RED 测试**

在 `ChatUiStateTest.kt` 添加：

```kotlin
@Test
fun imagePartSourceUsesImageContentBeforeFallbackLabel() {
    val part = UiMessagePartDraft(
        index = 1,
        type = UiMessagePartType.IMAGE,
        content = "https://example.com/reply.png",
        metadata = mapOf("mimeType" to "image/png"),
        stable = true,
    )

    assertEquals("https://example.com/reply.png", imagePartSource(part))
}
```

在 `ChatImageComponentsTest.kt` 添加：

```kotlin
composeRule.setContent {
    HarnessApkTheme {
        ChatImageThumbnail(
            image = ChatImageDisplay.Ready(testUri, "image/jpeg"),
            onOpen = { opened = true },
        )
    }
}
composeRule.onNodeWithContentDescription("打开图片预览").performClick()
assertTrue(opened)
```

- [ ] **Step 2: 运行 RED 测试**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
```

Expected: 缺少 `imagePartSource` 和图片组件。

- [ ] **Step 3: 实现消息图片和全屏预览**

创建 `ChatImageComponents.kt`：

```kotlin
sealed interface ChatImageDisplay {
    data class Ready(val uri: Uri, val mimeType: String) : ChatImageDisplay
    data object Loading : ChatImageDisplay
    data class Failed(val message: String) : ChatImageDisplay
}

@Composable
internal fun ChatImageThumbnail(
    image: ChatImageDisplay,
    onOpen: () -> Unit,
)

@Composable
internal fun ChatImagePreviewDialog(
    image: ChatImageDisplay,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveStatus: String?,
)
```

实现约束：

- 缩略图使用固定 `aspectRatio(4f / 3f)`、`widthIn(max = 260.dp)`、裁剪显示和 48dp 点击目标。
- `Ready` 点击打开 `Dialog`；预览使用 `ContentScale.Fit`，关闭按钮有内容描述“关闭图片预览”，保存按钮文字为“保存图片”。
- `Loading` 使用进度指示；`Failed` 显示“图片加载失败”和重试动作，不影响其它消息部件。
- `saveToMediaStore` 在 IO 协程调用；成功显示“已保存图片”，失败显示用户可读错误。

在 `ChatScreen` 为每条用户消息收集：

```kotlin
val attachments by container.chatRepository.observeAttachments(message.id).collectAsState(initial = emptyList())
```

将每个 `attachment.type == "image"` 映射为 `ChatImageThumbnail`，并将同一预览状态提升到消息行。将 `UiMessagePartType.IMAGE` 从 `MetadataPart` 改为图片组件，使用：

```kotlin
internal fun imagePartSource(part: UiMessagePartDraft): String = part.content.trim()
```

当模型图片是 HTTPS/data URI 时，在 `LaunchedEffect(source)` 中调用 `resolveDisplaySource` 和 `materialize`，并缓存为 `Ready` 本地 URI。

- [ ] **Step 4: 扩展设备预览与保存动作测试**

在 `ChatImageComponentsTest.kt` 添加：

```kotlin
composeRule.onNodeWithContentDescription("打开图片预览").performClick()
composeRule.onNodeWithContentDescription("关闭图片预览").assertIsDisplayed()
composeRule.onNodeWithText("保存图片").performClick()
assertEquals(1, saveCalls)
```

并为 `Failed` 状态断言“图片加载失败”与重试操作可见。

- [ ] **Step 5: 运行 GREEN 测试**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.ui.chat.ChatUiStateTest
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.chat.ChatImageComponentsTest
```

Expected: 图片消息、预览打开、保存回调和失败占位均通过设备测试。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/harnessapk/ui/chat/ChatImageComponents.kt app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt app/src/androidTest/java/com/harnessapk/ui/chat/ChatImageComponentsTest.kt
git commit -m "功能：渲染会话图片并支持预览保存"
```

---

### Task 4: 生命周期、远程图片与完整回归

**Files:**
- Modify: `app/src/main/java/com/harnessapk/chat/ChatImageStore.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatImageComponents.kt`
- Modify: `app/src/test/java/com/harnessapk/chat/ChatImageStoreTest.kt`
- Modify: `app/src/androidTest/java/com/harnessapk/ui/chat/ChatImageComponentsTest.kt`

**Interfaces:**
- Consumes: Task 1 本地资产和 Task 3 显示组件。
- Produces: HTTPS 缓存失败降级、MediaStore 写入验收和持久历史图片保证。

- [ ] **Step 1: 写入远程缓存 RED 测试**

在 `ChatImageStoreTest.kt` 添加：

```kotlin
@Test
fun materializeCachesHttpsImageAsManagedLocalUri() = runTest {
    httpClient.enqueueImage("https://example.com/reply.jpg", "remote-image", "image/jpeg")

    val image = store.materialize(ChatImageSource.Remote("https://example.com/reply.jpg", "image/jpeg"))

    assertTrue(image.uri.toString().contains(".fileprovider"))
    assertEquals("remote-image", readPersistedText(image.uri))
}

@Test
fun materializeRejectsNonImageHttpsResponse() = runTest {
    httpClient.enqueue("https://example.com/page", "<html/>", "text/html")

    assertFailsWith<AppError.Network> {
        store.materialize(ChatImageSource.Remote("https://example.com/page", null))
    }
}
```

- [ ] **Step 2: 运行 RED 测试**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest --tests com.harnessapk.chat.ChatImageStoreTest
```

Expected: HTTPS 图片尚未落盘或未校验 image Content-Type。

- [ ] **Step 3: 完成远程缓存与保存失败处理**

实现 `materialize`：

```kotlin
is ChatImageSource.Remote -> {
    val response = httpClient.get(source.httpsUrl)
    val mimeType = response.headers[HttpHeaders.ContentType]
        ?.substringBefore(';')
        ?.takeIf { it.startsWith("image/") }
        ?: throw AppError.Network("远程内容不是图片")
    persistBytes(response.bodyAsBytes(), mimeType)
}
```

为相同 HTTPS URL 在 `cacheDir/chat-image-cache/` 使用 SHA-256 文件名缓存；读取到有效缓存时不重复下载。`ChatImageComponents` 对 `AppError.Network` 保持 `Failed` 状态并提供重试，不让异常离开 Compose 协程。

- [ ] **Step 4: 运行完整回归与设备验收**

Run:

```bash
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug
ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.ui.chat.ChatCameraEntryTest,com.harnessapk.ui.chat.ChatImageComponentsTest
```

设备验收：

1. 输入框点击“添加图片”，确认“拍照”“从相册选择”均可见。
2. 拒绝相机权限，确认文字草稿仍在且相册入口可用。
3. 用模拟相机成功结果发送一张图片，重启应用后确认消息缩略图仍显示。
4. 点击缩略图，确认全屏预览、关闭与“保存图片”可用。
5. 输入 HTTPS 模型图片部件，确认成功缓存渲染；断网后确认失败占位与重试。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/harnessapk/chat/ChatImageStore.kt app/src/main/java/com/harnessapk/ui/chat/ChatImageComponents.kt app/src/test/java/com/harnessapk/chat/ChatImageStoreTest.kt app/src/androidTest/java/com/harnessapk/ui/chat/ChatImageComponentsTest.kt
git commit -m "完善：缓存模型图片并完成会话图片验收"
```
