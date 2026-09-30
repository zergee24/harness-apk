# 家庭消息通道（Family IM）P0 设计方案

> 状态：**设计稿（2026-09-04），未实施**。来源：2026-09-04 主会话讨论定稿。
> ~~阻塞项~~：原 M0（卓易通 push wake 到达率）随 2026-09-04 拍板「通知/推送退出 P0」整体解除；卓易通真机验证并入 M3 验收清单。
> 关联：生活 tab 家庭交付（.hconfig 0.5.0）、AI→人 触达（P2 预留）、工作简报推送（P2 预留）。

## 0. 一句话

在现有 relay 进程上加一个与 Codex 控制通道**零交集**的「家庭密文邮箱」：消息端到端加密后落在 relay 滚动窗口里，设备打开 app 即 HTTPS 拉取解密（P0 无推送、无通知）；relay/VPS 全程只见密文与元数据；客户端 Room 是历史的长期归属。

## 1. 产品定位与范围

**定位**：家庭双向消息流（留言投递系统），不是通用 IM。父母 ↔ Tony 的文本/图片留言；为 AI → 人 方向（agent 主动推简报/提醒）预留同一通道。

**P0 范围**：
- 1:1 会话（Tony ↔ 每位家人各一条），文本 + 图片
- 离线可靠投递（at-least-once + 去重），「已送达」状态（不含已读）
- 父母设备免注册：扫 Tony 端二维码即入会话
- 生活 tab 入口，兼容生活简洁模式
- **家人间互聊**（如 Mom↔Dad）：admin 撮合 + 带内密钥分发，父母端零操作（见 3.5）
- 应用锁（设备凭据优先 + 自设 PIN 降级）：防非家人拿到手机翻看或经调试拉起 UI（见 5.6）
- **无推送、无通知**：打开即同步，「有新留言」的唯一提示面 = 生活 tab 未读角标与会话列表预览（2026-09-04 拍板）

**P1（预留，不实施）**：≥3 人群聊（机制 = N 份 conv-invite，顺手）、成员自助建会话（需密钥协商设计）、「管理员不可读」模式（铸造后即弃）、语音消息、密钥备份并入 .hconfig、AI→人（kind=agent 消息）、推送+通知的可选回归（启用前需补跑卓易通 wake 到达 spike）。

**非目标（v1 永久）**：群聊、已读回执、撤回、输入中状态、在线状态、音视频通话、账号体系、服务端搜索、多端同步、双棘轮前向保密。

## 2. 核心架构决策（定稿）

| # | 决策 | 理由 |
|---|---|---|
| D1 | **独立 family realm**：relay 内新增 `internal/im` 模块，独立路由（`/v1/im/*`）、独立凭据（imDeviceToken）、独立状态目录（`im-data/`），不碰 `/v1/ws` 热路径 | 指令流量与内容流量语义不同（即发即弃 vs 暂存重投），混轨会被对方拖累；test 线 reducer 对未知帧严格（见 HANDOFF-agent-dashboard 跨线状态），老版本兼容风险归零 |
| D2 | **无 WSS、无推送：打开即同步（HTTPS pull）** | 家庭留言对及时性无硬要求，使用模式是「打开生活 tab 看一眼」；砍掉推送 = 砍掉最大工程风险（卓易通 wake 到达率）与阿里云依赖；服务端降为纯无状态 API + 存储，实现量骤减 |
| D3 | **密文邮箱 + 滚动窗口**：relay 存 AES-256-GCM 密文，ack 后 7 天清除、未 ack 30 天硬顶；Room 是历史长期归属 | 不让 VPS 变成需要永久备份的数据责任；新设备最多回溯 30 天，接受 |
| D4 | **每会话一把对称密钥（32B），管理员设备持有全部会话密钥**：Tony 手机 = family admin，生成所有会话密钥并经 QR 分发 | 复用现有配对 QR 携带 `pairingSecret` 的成熟流程；不上双棘轮——家庭 1:1 威胁模型用不上前向保密，换来的实现简单性巨大；admin 天然成为信任锚（换机重发 QR 即可恢复家人设备） |
| D5 | **投递语义**：发送方按会话单调 `senderSeq`（服务器强制拒重放），`messageId`（客户端 UUID）全局去重，at-least-once + 客户端 dedup，ack 后服务器标记已送达 | 与 remote 协议的 sequence/ack 心智一致，无新概念 |

**已否决的方案（勿走回头路）**：

1. **复用 `/v1/ws` 多路复用 IM 帧**：老版本 APK reducer 对未知帧严格（test 线已出过一次），且违反「指令与内容零交集」原则（dashboard HANDOFF 先例）。否。
2. **双棘轮/X3DH（Signal 级密钥协商）**：威胁模型不需要；密钥管理复杂度会吃掉整个项目。否。
3. **IM 走 WSS 长连接或推送唤醒**（P0）：见 D2。未来若需要及时性，先补跑卓易通 wake spike 再评估，不直接上长连接（长连接在卓易通杀后台下同样不可靠）。
4. **Telegram 云模式（服务器无限期持有全部历史、客户端薄壳）**：见 D3，服务器持钥换来的多端同步是我们明确不要的。
5. **父母设备 enroll 进现有 host/device 模型**：Codex remote 的 device 从属于唯一 host（Tony 的 Mac），家庭成员不是任何 host 的遥控器，混入会破坏凭据边界。否——family realm 独立 enroll。

## 3. 服务端设计（relay `internal/im`）

### 3.1 身份模型：凭据持有制（2026-09-04 拍板）

**身份 = 持有 admin 铸造的设备鉴权密钥，不以设备信息为准。** 设备标识（ANDROID_ID 等）不是秘密（任何 app/adb 可读）、卓易通下取值不可靠、恢复出厂即漂移——只可作展示元数据（deviceName），永不作为凭证。

**两把钥匙分离**：

| 钥匙 | 作用 | 出现位置 |
|---|---|---|
| 设备鉴权密钥 deviceAuthSecret（32B/设备） | 证明「我是哪个已注册设备」，API 鉴权 | QR 下发；上线只跑 SHA-256 |
| 会话密钥 conversationSecret（32B/会话） | 证明「我能读这条会话」，端到端加解密 | 只在 QR 与双方设备，永不上线 |

- **admin 是唯一密钥铸造点**（Tony 手机）：所有密钥由 admin 生成并进 QR——`{version, realm: "family", relayUrl, inviteTicket, conversationId, conversationSecret, deviceAuthSecret, memberName}`。服务器零铸造身份材料，只存 SHA-256 hash（沿用 `state/store.go` 惯例）。一次扫码 = 身份 + 会话全部就绪（密钥进入）。扫码 UX 复用 `RemoteSettingsScreen` 的扫 QR/相册解 QR。
- 部署时新增 env `HARNESS_FAMILY_BOOTSTRAP_TOKEN`（≥32 随机字节），**第一个**用它 enroll 的设备成为 admin（一次性使用，沿用 host bootstrap 惯例）。
- 邀请票分两类：**新成员**（建会话）与**重绑**（顶替既有会话的成员槽），都由 `POST /v1/im/invites` 创建，一次性、TTL 5 分钟。票只管「注册这一刻的入会资格」，之后身份全靠设备鉴权密钥。
- **鉴权**：`Authorization: Bearer <deviceId>.<base64url(sha256(deviceAuthSecret))>`，服务器比对存好的 hash；密钥本体不出现在任何请求。
- **设备记录带 admin 标记**：bootstrap enroll 的那台设备为 admin，独占 `POST /v1/im/invites`、`POST /v1/im/conversations` 权限。
- **吊销/换机**：admin 对该会话发「重绑」票（新 `deviceAuthSecret`，`conversationSecret` 复用）→ 新设备 enroll 成功时服务器自动顶替并吊销旧 deviceId（每成员 P0 一台设备）。怀疑泄漏同流程，旧密钥即刻失效。
- **卸载重装/清数据** = 密钥丢失 = 走重绑流程重扫 QR（HiBreak 有 `install -r` 偶发清数据的先例，重装后不要假设密钥还在）。
- 会话密钥轮换（可选工具，P1）：admin 换发新 `conversationSecret` 并向该会话全体成员重发 QR——旧历史旧钥仍可解（无前向保密，如实接受），新消息受新钥保护。

### 3.2 HTTP 路由（全部 Bearer imDeviceToken 鉴权）

| 路由 | 说明 |
|---|---|
| `POST /v1/im/enroll` | 邀请票 + deviceName + sha256(deviceAuthSecret) 换 deviceId（票一次性，TTL 5min） |
| `POST /v1/im/invites` | admin 创建邀请票（新成员 / 重绑既有成员） |
| `POST /v1/im/conversations` | admin 建会话：登记铸造好的 conversationId + 成员槽（恰 2 人），零密钥材料 |
| `GET /v1/im/conversations` | 本设备所属会话对账（id/成员/名称，无密钥），见 3.5 |
| `POST /v1/im/messages` | 批量投递信封（≤20 条/请求） |
| `GET /v1/im/sync` | 拉取本设备所有未 ack 消息（按 createdAt+seq 排序） |
| `POST /v1/im/ack` | 按 messageId 批量确认，服务器写 delivered 标记 |
| `GET /v1/im/history` | 会话保留窗口内按 seq 区间回捞（换机/补历史用） |
| `PUT /v1/im/blobs/{blobId}` `GET /v1/im/blobs/{blobId}` | 密文 blob 上传/下载，blobId=128bit 随机 hex，校验会话成员关系 |

鉴权：除 enroll/invites 外全部路由要求 3.1 的 Bearer（`deviceId.sha256(deviceAuthSecret)`），设备密钥本体不上线。中间件沿用：按 IP 限流、Origin 拒浏览器；**body 上限单独设 12MB**（现有 64KB 是控制通道的，不共用）。

### 3.3 存储与保留

- `im-data/` 目录：每会话一个 append-only JSONL（信封逐行追加 + fsync），`im-meta.json` 存凭据 hash、成员→设备映射、delivered 标记、游标（小文件，沿用 tmp+fsync+rename 原子写）。启动时重放 JSONL 重建索引，尾部半行截断丢弃。
- 不引入 sqlite：量级（家庭、万条内）用不上，且 bridge 侧已有 modernc 依赖离线拉不到的先例。
- **保留策略**：delivered 后 7 天清除；未 delivered 30 天硬顶；blob 同策略；每会话 blob 配额 200MB，超出按最旧清除并落 tombstone（客户端看到 seq 空洞 → UI 显示「更早消息仅存于双方设备」）。

### 3.4 无推送（2026-09-04 拍板）

P0 不接阿里云 push：无 wake、无通知渠道、无 PushTarget，`internal/push` 仅服务既有 Codex 链路。relay 对家庭 IM 是纯 HTTPS 服务，部署零推送凭据。消息到达的唯一提示面是客户端未读角标（5.3）。未来需要及时性时回归路径现成：enroll 加回 PushTarget、落库后触发 wake——届时先补跑卓易通到达率 spike。

### 3.5 会话创建与互聊撮合（P0，admin 撮合 + 带内密钥分发）

- **互聊仍由 admin 撮合，成员不自助建会话**。原因：两个尚无共享信道的成员之间没有安全信道传会话密钥——鸡生蛋。自助要么密钥经 QR 截图/第三方 app 中转（破 E2E 前提），要么上密钥协商（P0 不值）。
- **创建**：admin 选两名成员 → 本地铸造 conversationId + conversationSecret → `POST /v1/im/conversations`（admin 鉴权，P0 强制恰 2 人）→ 服务器登记会话与成员槽，仍零密钥材料。Tony↔家人的既有会话走 enroll 隐式创建不变，此路由只服务互聊。
- **密钥分发走带内，父母端零操作**：admin 经既有 E2E 会话（Tony↔Mom、Tony↔Dad）各发一条 `kind=conv-invite` 消息，密文体 = `{conversationId, conversationSecret, conversationName, members}`。收端解密后静默安装密钥、建本地会话，UI 渲染为「你被拉入“xxx”」，原始密钥永不显示。比第二次扫码安全（走既有 E2E 信道，无截图外泄面）、也省事。
- **待钥缓冲**：新会话密文可能先于邀请消息到达（跨会话无时序保证），客户端存为 `state=pending-key`，conv-invite 安装密钥后重处理。未知 kind 一律宽松忽略（存为未识别、不崩）——与 test 线 reducer 对未知帧严格性的教训相反，家庭客户端必须向后兼容。
- **对账**：`GET /v1/im/conversations` 返回本设备所属会话（id/成员/名称，无密钥）。客户端对账出「服务器已有、本地无钥」的会话挂 pending 标记；极端情况（设备离线超保留窗口致邀请被清）由 admin 重发邀请兜底。
- **幂等与轮换复用**：conv-invite 按 conversationId upsert——同钥重复收为 no-op，异钥更新即轮换（P1 轮换工具复用同一通道，不用新协议）。
- **信任锚的已知属性（刻意取舍）**：互聊密钥由 admin 铸造即进信任锚——**admin 理论上可读互聊内容**。家庭场景这是接受的，且它是该会话唯一的恢复/重签发路径；若未来要「管理员不可读」，P1 备选为铸造后即弃本地副本（牺牲恢复能力，重绑只能由持钥成员撮合）。
- **重绑扩展**：互聊会话的重绑票照 3.1 流程，QR 额外携带该会话 conversationSecret（admin 持有，能签发）。
- **不做（P0）**：成员自助建会话（见上）；≥3 人群聊（机制上只是 N 份 conv-invite，P1 顺手）；admin 移除会话成员（P1）。

## 4. 线协议

**信封**（明文元数据，relay 可见——元数据可见是已接受的边界）：

```json
{
  "version": 1,
  "messageId": "<client uuid>",
  "conversationId": "<hex>",
  "senderDeviceId": "<hex>",
  "senderSeq": 42,
  "kind": "text | image | conv-invite",
  "createdAt": 1757000000000,
  "blobId": "<hex 可空>",
  "nonce": "<base64url>",
  "ciphertext": "<base64url>"
}
```

- **加密**：AES-256-GCM，key = 会话密钥 32B 原文（与 `RemoteCrypto` 同构，无 HKDF），nonce 12B 随机，tag 128bit。
- **AAD 绑定**：`family-im-v1\0messageId\0conversationId\0senderDeviceId\0senderSeq\0kind\0blobId\0nonce`——防元数据篡改/跨会话重放；AAD 全部是发送前已知的字段（不绑服务器侧序号）。
- **明文体**（ciphertext 内）：text = UTF-8 正文（≤8KB）；image = `{width, height, mime, blobNonce}`，blob 本体用**同一会话密钥**另起 nonce 加密，每消息 1 blob，原图 ≤10MB；conv-invite = `{conversationId, conversationSecret, conversationName, members}`（用承载会话的密钥加密，见 3.5）。
- **序与重放**：`senderSeq` 由发送端按会话单调递增；服务器对 `(conversationId, senderDeviceId)` 拒绝 `senderSeq ≤ 已见最大值` 的信封；接收端按 messageId 去重（Room 主键兜底），按 createdAt 排序、seq 决胜。

## 5. 客户端设计（APK）

### 5.1 包结构与角色

- 新包 `com/harnessapk/familyim/`（协议/仓库/同步）+ `ui/family/`（界面）。
- **Tony 手机**：admin。能创建邀请、导出密钥备份；同时是普通收发端。
- **父母设备**：纯收发端。**只 enroll family realm，不 enroll Codex host**，与 remote 包凭据零交集。

### 5.2 Room 迁移（沿用手写 SQL 惯例注册进 AppContainer）

> 版本号以落地时 `AppDatabase` 现状为准：当前主库 v25（简报 P1），provider-claude 协议分支已把 25→26 预留（见记忆）；家庭 IM 落地时取下一个空闲版本号，不锁死 v26。

- `im_conversations`：id、对端成员名、对端 deviceId、lastMessageAt、unread、lastPreview（本地静态加密后的密文，见 5.5）
- `im_messages`：messageId(PK)、conversationId(FK CASCADE)、direction、senderDeviceId、senderSeq、kind、body（**静态密文**，见 5.5）、blobId、blobPath、createdAt、state(sending/sent/failed/received/pending-key)
- `im_outbox`：messageId、conversationId、kind、body/blobRef、attempts、nextAttemptAt（退避 `3s << n` cap 48s，沿用 `RemoteTransport` 惯例）
- **不做 `im_messages_fts`**：字段级静态加密后，FTS 索引会把明文分词落盘，反而成为最大泄漏面。P0 本地检索降级为「会话内对已解密消息的内存过滤」；跨会话全局搜索后置再议

**密钥不进 Room**：SharedPreferences `family_im_keys`，经 `ResilientStringCipher` 加密（对应 `remote_profiles` 存 `pairingSecret` 的既有做法），存每会话的 `conversationSecret` + `deviceAuthSecret`（都来自 QR）；本地静态加密密钥另用一把（见 5.5）。三把钥匙、三个目的，互不复用。

### 5.3 同步（无长连接、无推送、无通知）

- **触发**：冷启、回前台、进生活 tab、本端发送成功后。均为轻量 HTTPS sync（文本几 KB，秒级）；不建前台服务、不建通知渠道、不做前台定时轮询。
- **未读提示面**：生活 tab「家人」入口的未读角标 + 会话列表最后一行预览（解密后明文，存本地静态加密的 lastPreview）。产品心智是留言板：「每天打开生活 tab 看一眼」，不追及时性。
- 图片 blob 懒下载：sync 只拉信封与文本，blob 在会话页可见时或 Wi-Fi 下拉取，落盘保持密文（见 5.5）。
- **已送达语义**：服务器在收端任意一次 sync 时把未 ack 消息标记 delivered，发送端下次 sync 读到「已送达」——无推送下这就是自然节奏。

### 5.4 UI

- 生活 tab（`ConversationListScreen`）加「家人」入口卡，**简洁模式保留**（父母唯一入口，不能藏在 WORK 里）。
- `FamilyInboxScreen`（会话列表）→ `FamilyChatScreen`（消息流 + 发送）：大字号、大触点，对齐生活简洁模式；e-ink 设备遵守局部失效纪律（HiBreak 画布 spike 的既有结论）。
- 设置新增「家庭消息」区：发起邀请（admin）、**新建互聊**（admin 选两名成员 → 铸钥 → 登记会话 → 双通道发 conv-invite，见 3.5）、密钥备份导出/导入（passphrase 加密 JSON 文件，P0 手动；P1 并入 .hconfig）。

### 5.5 本地静态加密（防 USB 调试/文件拷取面，2026-09-04 补充）

**分层防线，加密只是其中一层**（对照当前事实：`allowBackup=false`、`fullBackupContent=false`、data_extraction_rules 全排除——adb backup/云备份路已堵；release 包 run-as 不可用已实测）：

1. **纪律层（真正的主墙，零成本）**：家庭设备只装 release 包（debug 变体 applicationId 带 `.debug` 后缀且 debuggable，run-as 直通沙箱，静态加密形同虚设）；`familyim` 代码**零日志纪律**——正文、密钥、blob 引用一律不进 logcat（logcat 是 USB 调试下唯一还开着的明文通道）。
2. **字段级静态加密（纵深，P0 做）**：`im_messages.body`、`im_conversations.lastPreview`、outbox 明文体、已下载 blob 文件统一存 AES-256-GCM 密文；本地存储密钥（Keystore 优先 + 软件回退，复用 `ResilientStringCipher` 模式）与 E2E 会话密钥分离，永不出设备、不进任何备份（备份它等于白加密）。渲染时解密进内存（GCM 微秒级，e-ink 无压力）；**图片不解码落盘明文文件**——blob 密文存 `files/im-blobs/`，Bitmap 从内存字节解码，临时明文只进 cache 并即用即删。
3. **诚实边界（写进验收认知）**：静态加密防「拷文件」（run-as 拖库、未来任何备份/迁移通道、debug 误装的顺手拖库），**不防「拿到 app 执行权」**——debug 包 + run-as 或 root 下，攻击者能以 app uid 调 Keystore 解密，这是构建纪律的领地而不是加密的。家人解锁后翻看手机的威胁，杠杆是应用锁（见 Open question 5），不是静态加密。

**已否决**：SQLCipher 整库加密——能顺带保住 FTS，但引入 native .so，卓易通兼容层的 ABI 风险不值得，且 codebase 目前零 native 加密依赖。

**接受的代价**：正文不可 SQL 查询（按元数据过滤已够 P0）；FTS 砍掉（见 5.2）；本地存储密钥丢失 = 本地历史不可解——与「设备坏 = 本地历史没了」现状一致，30 天服务器窗口 + admin 信任锚部分兜底，不新增备份义务。

### 5.6 应用锁（P0，2026-09-04 拍板采纳）

**定位**：威胁 = 「非家人拿到手机」的两种取数路径。应用锁守 **UI 面**（直接翻看、以及经 `adb am start` 拉起界面），5.5 静态加密守 **文件面**，5.5 纪律层守 **logcat/构建面**——三层互补，各管一路。

- **锁什么**：整个 app，冷启与回前台即锁（宽限期可配，默认 0 秒）。家庭设备上生活 tab 就是主使用面，锁 app 比锁单一入口简单可靠，AI 会话同样被覆盖。
- **解锁凭据**：`BiometricPrompt` + 设备凭据（指纹/人脸/锁屏密码）优先——不自建 PIN 体系、不存任何 PIN 材料；设备未设锁屏 → 降级为应用内自设 PIN（salt + 慢哈希本地存，连错 5 次指数退避），并在设置内引导「建议给手机设锁屏密码」。
- **分发**：开关进 .hconfig 配置包（沿用生活简洁模式进配置包的先例），批量交付父母设备时默认开。
- **调试纪律衔接**：家庭生产设备保持开发者选项/USB 调试关闭（交付后不需要 adb）；HiBreak 是 Tony 的测试设备，属例外。应用锁对调试拉起 UI 的路径同样有效：`am start` 打开后停在锁屏页，内容不可见。
- **诚实边界**：debuggable 包 + run-as 或 root 的执行权攻击，应用锁与静态加密都防不住——主墙仍是 5.5 的构建纪律（家庭设备 release-only）。

## 6. 安全边界

- **relay/VPS 可见**（P0 无推送，阿里云不在家庭 IM 链路上）：元数据（谁、何时、频率、blob 大小、seq）+ 密文。**不可见**：任何明文、任何密钥材料（邀请票只证明 enroll 资格，两把密钥只存在于 QR 与双方设备）。
- 前向保密：**无**（接受，见 D4）。会话密钥泄露 = 该会话全部历史可解。
- 信任锚 = admin 设备：它持有全部会话密钥。Tony 手机丢失 → 用备份文件恢复（无备份 = 家庭历史密文全部不可解，如实接受并靠备份纪律兜底）；家人设备丢失 → admin 重发 QR 即可，无历史损失（admin 可代持回捞窗口内的密文）。
- 重放：服务器按 `(conversationId, senderDeviceId, senderSeq)` 拒重放；AAD 绑定防跨会话搬移。
- **本地静态**：消息正文/预览/blob 文件在设备上只有密文（5.5）；明文只存在于内存与 UI 层；logcat 无业务内容。

## 7. 里程碑

**M0（原阻塞 spike 已解除）**：通知/推送退出 P0 后，「卓易通 push wake 到达率」不再是阻塞项。真机验证并入 M3 验收：打开即同步的延迟与流量、应用锁体验、e-ink 局部刷新、换机重扫 QR。

**M1 relay**：`internal/im` realm + 密文邮箱 + Bearer 鉴权 + 会话创建/对账路由（零推送凭据依赖）；用 curl 脚本做双端到端验证（加密逻辑先用测试向量跑通 AAD 一致性）。
**M2 Tony 端**：admin enroll、新成员/重绑 QR 生成、**新建互聊撮合 + conv-invite 发送**、收发、Room 迁移（版本号按 5.2 落地时定）、字段级静态加密。
**M3 父母端**：扫码入会、conv-invite 接收 + pending-key 缓冲、生活 tab 入口 + 简洁模式 + 未读角标、图片、应用锁；卓易通真机验收。
**M4 发布**：双通道（test→prod）、密钥备份导出/导入、真机交付回填。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 无推送 → 留言不被及时看到 | 产品明确为留言板心智（未读角标 + 日常打开生活 tab）；需要及时性时走 P1 推送回归（先补 spike） |
| 邀请 QR 截图外泄（含两把密钥） | 票一次性 + 5min TTL；deviceAuthSecret 可由 admin 重发 QR 即刻轮换；conversationSecret 泄漏属会话级事故，走 P1 轮换工具 |
| 互聊内容对 admin 可读 | 刻意取舍：信任锚 = 恢复路径，拍板记录明示；「管理员不可读」模式（铸造即弃）留 P1 |
| JSONL 追加损坏 | fsync + 启动重放截断半行；`im-meta.json` 原子写 |
| VPS 磁盘增长 | blob 配额 200MB/会话 + TTL 双保险 |
| 老 APK / 老 relay 兼容 | 全新 namespace，老版本视而不见；relay 未配 family bootstrap token 时 realm 整体关闭 |
| 密钥丢失 | admin 信任锚 + passphrase 备份文件；UI 首次启用时强制引导备份 |
| 阿里云 push 凭据 | 复用现有 env（已在生产），无新增云资源 |
| 误装 debug 包 / logcat 泄漏 | 分发纪律：家庭设备只装 release（`.debug` 后缀包可肉眼识别）；familyim 零日志纪律进 code review 检查项 |
| 父母设备未设锁屏 | 应用锁降级自设 PIN（慢哈希 + 连错退避），设置内引导设锁屏；PIN 只挡 UI，文件面靠 5.5 静态加密兜底 |

## 9. Open questions（需拍板）

1. **家人间互聊**（Mom↔Dad）P0 砍掉是否可接受？若保留，admin 撮合第二条会话（各自 QR 带同一把新会话密钥）成本也不高，倾向 P0 就做。
2. **「已送达」可见性**：服务器 delivered 标记回传发送端（P0 已含），要不要在气泡上显示？（建议显示，成本低、对父母心智友好）
3. **图片压缩策略**：原图直传（≤10MB）还是发送端压到长边 2048？（e-ink 显示场景建议发送端压缩，省流量省 blob 配额）
4. **Tony 的 Mac bridge 要不要有任何家庭消息可见性**？当前设计为完全不可见（零交集），维持即可。

**拍板记录**：
- 2026-09-04：应用锁进 P0，目的锁定为「防非家人拿到手机乱翻看、或经调试拉起 UI/取数」，设计见 5.6。
- 2026-09-04（二次）：① 通知与推送整体退出 P0，打开即同步、留言板心智，原阻塞 spike 解除；② 身份采用凭据持有制——admin 唯一铸造全部密钥进 QR，服务器只存 hash，设备信息不作为凭证，见 3.1。
- 2026-09-04（三次）：家人间互聊进 P0——admin 撮合 + 带内 conv-invite 分发，成员不自助建会话；admin 可读互聊为已知取舍，见 3.5。
