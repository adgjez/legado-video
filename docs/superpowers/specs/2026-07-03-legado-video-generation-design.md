# Legado 视频生成 设计

- 日期：2026-07-03
- 状态：已确认，待实现
- 相关项目：
  - Legado（阅读Sigma）`https://github.com/Luoyacheng/legado`（gedoor/legado 的 fork，Kotlin Android 应用）
  - ArcReel `https://github.com/ArcReel/ArcReel`（仅参考产品思路：小说→剧本→画面→视频；不移植代码、不桥接服务端）

## 1. 目标与范围

在 Legado Android 端原生新增「从书本文本生成短视频」能力，参考 ArcReel 的产品形态（小说→剧本→画面→视频），但：

- **不移植 ArcReel 代码**（ArcReel 是服务端 Python/前端，技术栈不通）
- **不桥接 ArcReel 服务端**（用户无需自部署任何外部服务）
- AI 能力走**云 API**（首发 Agnes），编排与视频合成在**端内原生**完成

### 1.1 已确认决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 集成形态 | 端内原生开发，参考 ArcReel 产品思路 | 不依赖外部服务，体验完整 |
| AI 能力来源 | 全流程调云 API + 原生合成 | 端内 AI 模型不现实，云 API 免费可用 |
| 入口 | 阅读页（当前章/选区/N章）+ 书籍详情页（章节区间）| 覆盖阅读场景与整书场景 |
| 首发 Provider | Agnes（OpenAI 兼容，免费无限调用）| 一套 Key 覆盖 LLM/图/视频 |
| 编排方案 | 方案 C：LLM 产出结构化分镜表 + 确定性并行执行引擎 | 兼顾灵活与可靠，可断点续传 |
| 镜头生成 | 两步：图(agnes-image-2.1-flash) → 图生视频(agnes-video-v2.0) | 风格统一可控，质量高 |
| 音频 | 不做 TTS；视频模型 agnes-video-v2.0 自带音画同步音频 | 简化流水线，质量更高 |

### 1.2 不做（YAGNI）

- 不做端内 AI 模型（MLC/ONNX）
- 不做 TTS 配音（视频模型自带音频）
- 不做 Agent 自主编排（保留升级口子，首发不实现）
- 不做豆包/通义等其他 Provider（保留可插拔接口，首发仅 Agnes）
- 不做云同步 / 在线分享平台

## 2. 总体架构

新增 `video` 子模块，分层清晰、可独立理解与测试。

### 2.1 包结构

```
io.legado.app
├─ video/                          # 新增：视频生成核心
│  ├─ provider/                    # 可插拔 AI Provider
│  │  ├─ VideoGenProvider.kt       # interface
│  │  ├─ AgnesProvider.kt          # 首发：OpenAI 兼容
│  │  └─ ProviderConfig.kt
│  ├─ pipeline/                    # 方案 C 混合编排
│  │  ├─ Storyboard.kt             # 分镜表模型 (JSON)
│  │  ├─ StoryboardBuilder.kt      # 调 LLM 产出分镜表
│  │  ├─ ShotExecutor.kt           # 并行调度 图→图生视频
│  │  └─ VideoComposer.kt          # Media3 Transformer 拼接 MP4
│  ├─ model/VideoJob.kt            # Room 实体
│  └─ VideoGenEngine.kt            # 对外门面：start/resume/cancel
├─ data/dao/VideoJobDao.kt         # 新增 DAO
├─ service/VideoGenService.kt      # 新增：前台服务 + 进度通知
└─ ui/book/video/                  # 新增 UI
   ├─ VideoGenConfigDialog.kt      # 范围/时长/风格配置
   ├─ VideoGenActivity.kt          # 进度 + 预览
   └─ VideoListActivity.kt         # 历史/重播/分享
```

### 2.2 复用现有基础设施

| 现有模块 | 复用方式 |
|---|---|
| `help/http/HttpHelper.kt`（okHttpClient） | Provider 发起 HTTP 请求 |
| `help/exoplayer/` + `model/VideoPlay.kt` + `service/VideoPlayService.kt` | 产出 MP4 的预览播放 |
| `help/coroutine/`（Coroutine/CoroutineContainer） | 协程调度与取消 |
| `help/config/AppConfig.kt` + `constant/PreferKey.kt` | 设置项持久化 |
| `model/webBook/BookContent.kt` | 取章节正文 |
| `service/` 前台服务模式 + `startForegroundServiceCompat` | 长任务服务 |
| `constant/NotificationId.kt` | 通知 ID |

### 2.3 不复用（明确边界）

- 不复用 `httpTTS` / `HttpReadAloudService` / `AnalyzeUrl`（无 TTS）
- 不复用 `gsyVideo`（预览用 ExoPlayer，合成用 Media3 Transformer）

## 3. 流水线详解（方案 C 混合编排）

### 3.1 阶段一：采集

- 阅读页入口：取当前章节正文 / 选中文字 / 连选 N 章正文
- 详情页入口：取章节区间正文
- 复用 `webBook.BookContent` 取正文，拼接成单一文本输入

### 3.2 阶段二：剧本（StoryboardBuilder）

- 调 Agnes `agnes-2.0-flash`（1M 上下文，足够整章/多章）
- Prompt 约束输出 **JSON 分镜表**，结构如下：

```json
{
  "title": "镜头组标题",
  "shots": [
    {
      "index": 0,
      "imagePrompt": "雨夜霓虹街道，赛博朋克风格，远景",
      "motionHint": "缓慢推进",
      "durationMs": 5000,
      "subtitle": "雨落霓虹街"
    }
  ]
}
```

- 单次 LLM 调用即可（不迭代）
- 可选用 tool calling 约束结构（为将来升级到 Agent 编排留口子）
- 失败重试 3 次；解析失败回退到更严格的 schema 提示词重试一次

### 3.3 阶段三：并行执行（ShotExecutor）

每镜头两步：

1. `agnes-image-2.1-flash` 文生图 → 关键帧图片 URL（下载到本地缓存目录）
2. `agnes-video-v2.0` 图生视频（传入图片 URL + motionHint + durationMs）→ 带音轨视频片段 URL（下载到本地）

调度策略：
- `Semaphore` 限并发（默认 2，可配）
- 每镜头指数退避重试（3 次）
- 每镜头产物本地路径写回 `VideoJob.storyboardJson`（断点续传：重跑只补缺失镜头）
- 单镜头失败可标记跳过（不阻塞整体）

视频生成 API 是**异步任务**模式：提交 → 轮询状态 → 取结果 URL。`generateVideo()` 内部封装：
- 提交任务，拿到 task_id
- 轮询（间隔 5s，超时 5min）
- 完成 → 返回视频 URL

### 3.4 阶段四：合成（VideoComposer）

- 用 **Media3 Transformer** 按镜头顺序拼接多个带音轨视频片段
- 处理：转场（淡入淡出）、音轨衔接（默认顺序衔接，不做混音）
- 编码：1080p MP4，输出到书籍缓存目录
- 失败回退：单镜头缺失则跳过该镜头

### 3.5 阶段五：产出

- `VideoJob.outputPath` 入库
- 预览：复用 `model/VideoPlay` + ExoPlayer
- 分享：通过 `Intent.ACTION_SEND` 分享 MP4 文件

## 4. Provider 抽象（可插拔）

```kotlin
interface VideoGenProvider {
    val id: String
    suspend fun chatLLM(messages: List<Msg>, jsonSchema: String? = null): String
    suspend fun generateImage(prompt: String, size: String): String  // 返回本地路径或 url
    suspend fun generateVideo(prompt: String, imageUrl: String, durationMs: Int): String  // 返回本地路径或 url
}
```

首发 `AgnesProvider`：
- base_url：`https://api.agnes-ai.com/v1`
- 认证：`Authorization: Bearer <API_KEY>`
- OpenAI 兼容接口
- 模型：`agnes-2.0-flash`（文本）/ `agnes-image-2.1-flash`（图）/ `agnes-video-v2.0`（视频）

后续添加豆包/通义只需实现接口，配置页切换 `videoGenProvider`。

## 5. 入口（两个，都做）

### 5.1 阅读页入口

- 在 `ui/book/read/ReadMenu.kt` 底部菜单新增「生成视频」按钮
- 点击 → `VideoGenConfigDialog`（范围选择：当前章 / 选中文字 / 连选 N 章）
- 确认 → 启动 `VideoGenService`

### 5.2 书籍详情页入口

- 在 `ui/book/info/BookInfoActivity` 新增按钮
- 点击 → `VideoGenConfigDialog`（章节区间选择，做整书预告片）
- 确认 → 启动 `VideoGenService`

## 6. 数据模型

```kotlin
@Entity(tableName = "videoJobs")
data class VideoJob(
    @PrimaryKey val id: Long,            // System.currentTimeMillis()
    val bookId: Long,
    val bookName: String,
    val chapterRange: String,            // "ch12" / "ch1-ch3" / "selection"
    val provider: String,                // "agnes"
    var status: Int,                     // 0待生成 1剧本 2执行 3合成 4完成 5失败 6取消
    var progress: Int,                   // 0-100
    var storyboardJson: String?,         // 分镜表 + 每镜头产物路径（断点续传用）
    var outputPath: String?,             // 成品 MP4 路径
    val createdAt: Long,
    var errorMsg: String?
)
```

- Room v89 → v90，新增 `VideoJobDao` + AutoMigration（或手写 migration）
- `AppDatabase.kt` 注册新实体与 DAO

## 7. 设置项

`PreferKey` 新增：
- `agnesApiKey`：Agnes API Key
- `agnesBaseUrl`：默认 `https://api.agnes-ai.com/v1`
- `videoGenProvider`：默认 `"agnes"`
- `videoGenImageSize`：默认 `1024x576`（16:9）
- `videoGenConcurrency`：默认 `2`
- `videoGenTargetDuration`：默认 `30`（秒，目标总时长，LLM 据此决定镜头数）

设置页新增「视频生成」分类：API Key 输入、Provider 选择、图片尺寸、并发数、目标时长。

**无 TTS 相关项**（已确认不做 TTS）。

## 8. 可靠性

- **断点续传**：每镜头产物路径持久化到 `storyboardJson`，失败/重跑只补缺失镜头
- **取消**：协程 `Job.cancel()`，`VideoGenService` 停止
- **前台服务** + 进度通知（复用 `NotificationId`、`startForegroundServiceCompat`）
- **错误处理**：
  - API 限流（RPM）→ 指数退避重试
  - 网络/解析失败 → 单镜头标记失败，可跳过或重试
  - 视频任务轮询超时 → 标记该镜头失败
  - LLM 产出非法 JSON → 严格 schema 重试一次，再失败则整体失败

## 9. 依赖

`app/build.gradle` 新增：
- `androidx.media3:media3-transformer`（同族，轻量，用于视频拼接）

已存在无需新增：OkHttp、Kotlin Coroutines、Room、ExoPlayer、AndroidX Core。

## 10. 测试

- `AgnesProvider` 单测：mock OkHttp，验证请求体（OpenAI 兼容格式）、响应解析、视频任务轮询逻辑
- `ShotExecutor` 单测：fake provider，验证并行调度、重试、断点续传、单镜头跳过
- `VideoComposer` 单测：固定素材，断言产出 MP4 可播放、时长≈预期
- `StoryboardBuilder` 单测：mock LLM 响应，验证 JSON 解析与容错

## 11. 实现顺序（粗粒度，供 writing-plans 细化）

1. 数据层：`VideoJob` 实体 + `VideoJobDao` + Room migration v90
2. Provider 层：`VideoGenProvider` 接口 + `AgnesProvider` 实现 + 单测
3. 流水线：`Storyboard` → `StoryboardBuilder` → `ShotExecutor` → `VideoComposer` + 单测
4. 服务层：`VideoGenService` 前台服务 + `VideoGenEngine` 门面
5. 设置：`PreferKey` 新增项 + 设置页「视频生成」分类
6. UI 入口：阅读页 `ReadMenu` 按钮 + 详情页 `BookInfoActivity` 按钮 + `VideoGenConfigDialog`
7. UI 产出：`VideoGenActivity`（进度+预览）+ `VideoListActivity`（历史）
8. 依赖与构建：`app/build.gradle` 加 media3-transformer

## 12. 未来扩展（不在本次范围）

- Agent 自主编排：把 `StoryboardBuilder` 升级为 Agent，LLM 自主拆解任务
- 其他 Provider：豆包、通义、OpenAI 兼容
- 整书自动分集：按字数自动切分多集
- 云同步 / 在线分享
