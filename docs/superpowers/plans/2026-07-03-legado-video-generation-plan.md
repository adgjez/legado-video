# Legado 视频生成 实现计划

- 日期：2026-07-03
- 对应 spec：`docs/superpowers/specs/2026-07-03-legado-video-generation-design.md`
- 目标：在 Legado Android 端原生实现「小说→视频」生成，参考 ArcReel 产品思路

## 约束与环境

- 工作目录：`/workspace/legado`（已 clone，main 分支）
- 当前 Room version：89（`AppDatabase.kt:70`），将升级到 90
- 项目用版本目录 `gradle/libs.versions.toml`，media3 版本 `1.8.0`
- 前台服务模板模仿 `CacheBookService.kt`
- 偏好读写模仿 `AppConfig.kt` 直读型字段
- **本沙箱无 Android SDK / Gradle 构建环境**，无法执行编译与单测；代码正确性靠对照真实代码与仔细审查保证，最终构建验证在用户本地进行
- 遵循 TDD 思想：能写单测的纯逻辑层（Provider 解析、ShotExecutor 调度、Storyboard 解析）配单测；Android 强相关层（Service/UI/Room）以代码审查为主

## 阶段划分

### 阶段 0：构建依赖与常量
- [ ] T0.1 `gradle/libs.versions.toml` 在 `media3-session`（行 125）后加 `media3-transformer = { module = "androidx.media3:media3-transformer", version.ref = "media3" }`
- [ ] T0.2 `app/build.gradle` dependencies 加 `implementation(libs.media3.transformer)`
- [ ] T0.3 `constant/PreferKey.kt` 在 `autoCheckNewBackup`（行 183）后加 6 个 key：`agnesApiKey`、`agnesBaseUrl`、`videoGenProvider`、`videoGenImageSize`、`videoGenConcurrency`、`videoGenTargetDuration`
- [ ] T0.4 `constant/NotificationId.kt` 在 `VideoPlayService = 108`（行 16）后加 `VideoGenService = 109`
- [ ] T0.5 `constant/IntentAction.kt` 加 `videoGenStart`、`videoGenStop`
- [ ] T0.6 `strings.xml` 加视频生成相关字符串（video_gen、video_gen_running、video_gen_done 等）

### 阶段 1：数据层
- [ ] T1.1 新建 `data/entities/VideoJob.kt`（模仿 `HttpTTS.kt`），字段见 spec §6
- [ ] T1.2 新建 `data/dao/VideoJobDao.kt`（模仿 `HttpTTSDao.kt`）：`insert`/`update`/`delete`/`getById`/`getByBook`/`getAll`/`getRunning`
- [ ] T1.3 `AppDatabase.kt`：entities 数组（行 72-76）加 `VideoJob::class`；行 149 后加 `abstract val videoJobDao: VideoJobDao`；version 89→90；autoMigrations 末尾加 `AutoMigration(from = 89, to = 90)`
- [ ] T1.4 编译生成 `app/schemas/io.legado.app.data.AppDatabase/90.json`（用户本地构建时生成，提交之）

### 阶段 2：Provider 抽象 + Agnes 实现
- [ ] T2.1 新建 `video/provider/VideoGenProvider.kt`：interface（`chatLLM`/`generateImage`/`generateVideo`），含 `Msg` data class
- [ ] T2.2 新建 `video/provider/ProviderConfig.kt`：从 AppConfig 读 base_url/key/model 名
- [ ] T2.3 新建 `video/provider/AgnesProvider.kt`：用 `HttpHelper.okHttpClient` 实现 interface
  - `chatLLM`：POST `/v1/chat/completions`，OpenAI 兼容，解析 `choices[0].message.content`
  - `generateImage`：POST `/v1/images/generations`，`agnes-image-2.1-flash`，返回 url
  - `generateVideo`：异步任务模式——POST 提交拿 task_id → 轮询（5s 间隔，5min 超时）→ 取视频 url；支持 `imageUrl` 图生视频
- [ ] T2.4 单测 `AgnesProviderTest.kt`：mock OkHttp（用 MockWebServer），验证请求体格式、响应解析、轮询逻辑

### 阶段 3：流水线
- [ ] T3.1 新建 `video/pipeline/Storyboard.kt`：data class（`title`、`List<Shot>`），Shot 含 `index/imagePrompt/motionHint/durationMs/subtitle/imagePath/videoPath/status/errorMsg`
- [ ] T3.2 新建 `video/pipeline/StoryboardBuilder.kt`：调 `provider.chatLLM`，prompt 约束 JSON schema，解析容错（重试 3 次，严格 schema 兜底 1 次）
- [ ] T3.3 单测 `StoryboardBuilderTest.kt`：mock provider 返回合法/非法 JSON，验证解析与重试
- [ ] T3.4 新建 `video/pipeline/ShotExecutor.kt`：按分镜表并行执行（Semaphore 限并发），每镜头 图→下载→图生视频→下载，产物路径写回 Shot，指数退避重试 3 次，断点续传（跳过已有产物），单镜头失败可跳过
- [ ] T3.5 单测 `ShotExecutorTest.kt`：fake provider，验证并行/重试/断点续传/跳过
- [ ] T3.6 新建 `video/pipeline/VideoComposer.kt`：用 Media3 Transformer（`Transformer` + `EditedMediaItem` 序列 + `ConcatenatingMediaSource` 等价 API）拼接多段视频为 1080p MP4，输出到书籍缓存目录；回调进度
- [ ] T3.7 单测 `VideoComposerTest.kt`（如可在 JVM 跑）：固定素材断言产出文件存在且可读

### 阶段 4：服务与门面
- [ ] T4.1 新建 `video/VideoGenEngine.kt`：object 门面，`start(job)`/`resume(job)`/`cancel(job)`，编排 阶段2-3-合成，更新 `VideoJob` 状态/进度，发 EventBus
- [ ] T4.2 新建 `service/VideoGenService.kt`：模仿 `CacheBookService`，前台服务+进度通知+协程，`onStartCommand` 分发 start/stop，调 `VideoGenEngine`
- [ ] T4.3 `AppConfig.kt` 加 6 个直读型字段（模仿 `ttsEngine`/`threadCount`）

### 阶段 5：设置页
- [ ] T5.1 新建 `res/xml/pref_config_video.xml`：API Key（EditTextPreference，掩码）、BaseUrl、Provider 选择、图片尺寸、并发数（SeekBar）、目标时长（SeekBar）
- [ ] T5.2 新建 `ui/config/VideoConfigFragment.kt`：模仿 `OtherConfigFragment`
- [ ] T5.3 `ConfigTag.kt` 加 `VIDEO_CONFIG`；`ConfigActivity.kt` 加分支
- [ ] T5.4 `pref_main.xml` 在 setting category 加「视频生成」跳转项

### 阶段 6：UI 入口
- [ ] T6.1 `view_read_menu.xml` 在 `ll_bottom_bg` 仿 `ll_catalog` 加 `ll_video_gen`（图标+文字）
- [ ] T6.2 `ReadMenu.kt`：`initView` 设颜色、`bindEvent` 绑点击、`CallBack` interface 加 `onClickVideoGen()`
- [ ] T6.3 `ReadBookActivity.kt` 实现 `onClickVideoGen()`：弹 `VideoGenConfigDialog`
- [ ] T6.4 `activity_book_info.xml` 在 `fl_action` 加 `tv_video_gen`（AccentBgTextView）
- [ ] T6.5 `BookInfoActivity.kt` 绑点击 → 弹 `VideoGenConfigDialog`
- [ ] T6.6 新建 `ui/book/video/VideoGenConfigDialog.kt`：选范围（当前章/选区/N章/章节区间）、目标时长，确认后建 `VideoJob` 入库并启动 `VideoGenService`
- [ ] T6.7 新建 `ui/book/video/VideoGenActivity.kt`：展示进度（订阅 EventBus）+ 完成后预览（ExoPlayer 播本地 MP4）+ 分享按钮
- [ ] T6.8 新建 `ui/book/video/VideoListActivity.kt`：历史 VideoJob 列表，点击重播/分享/删除

### 阶段 7：图标与资源
- [ ] T7.1 新增 `ic_video_gen` 矢量图标（drawable）
- [ ] T7.2 字符串资源补全

### 阶段 8：验证
- [ ] T8.1 代码审查：对照 spec 逐项检查
- [ ] T8.2 提示用户本地 `./gradlew assembleDebug` + 跑单测
- [ ] T8.3 提示用户填 Agnes API Key 后端到端测试一次生成

## 实现顺序与依赖

线性依赖：0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8
其中 2、3 内部单测可与实现同步。5、6 可部分并行（不互相依赖）。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| Agnes video API 异步轮询细节未知（文档未给全） | 实现时按通用异步任务模式（submit→poll task_id），加超时与重试；用户测试时若 API 形态不同再调整 |
| Media3 Transformer 拼接多段带音轨视频 API 复杂 | 先用最简 `EditedMediaItem` 序列 + `Transformer.start`；转场用 `Presentation` 缩放统一分辨率 |
| 无构建环境无法验证编译 | 严格对照真实代码写法，逐文件审查；最终由用户本地构建 |
| Room AutoMigration 需 schema 文件 | 提示用户首次构建生成 90.json 并提交 |
