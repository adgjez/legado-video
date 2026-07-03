package io.legado.app.video.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Agnes AI Provider 实现。
 *
 * - API 兼容 OpenAI 格式
 * - base_url 默认 https://apihub.agnes-ai.com/v1（官方 CLI 默认）
 * - 认证：Authorization: Bearer <API_KEY>
 * - 模型：agnes-2.0-flash（文本）/ agnes-image-2.1-flash（图）/ agnes-video-v2.0（视频）
 *
 * 视频生成是异步任务模式：提交拿 task_id → 轮询状态 → 取结果 URL。
 *   POST /v1/videos 提交 → 返回 {id/task_id/video_id}
 *   GET  /agnesapi?video_id=<id>&model_name=<model> 轮询（主）
 *   GET  /v1/videos/{id} 轮询（降级，兼容旧任务）
 * 结果 URL 字段：url | remixed_from_video_id | video | output_url
 */
class AgnesProvider(
    private val baseUrl: String,
    private val apiKey: String?
) : VideoGenProvider {

    override val id: String = "agnes"

    private val client by lazy { okHttpClient }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun authHeader(): String = "Bearer ${apiKey?.takeIf { it.isNotBlank() } ?: ""}"

    private fun requireKey() {
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("Agnes API Key 未配置")
        }
    }

    private fun url(path: String): String {
        val base = baseUrl.trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    override suspend fun chatLLM(
        messages: List<VideoGenProvider.Msg>,
        jsonSchema: String?,
        model: String?
    ): String {
        requireKey()
        val arr = com.google.gson.JsonArray()
        // 若要求 JSON 输出，先放 system 约束消息
        if (jsonSchema != null) {
            arr.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", "严格按 JSON 格式输出，不要 markdown 代码块。Schema: $jsonSchema")
            })
        }
        messages.forEach { m ->
            arr.add(JsonObject().apply {
                addProperty("role", m.role)
                addProperty("content", m.content)
            })
        }
        val body = JsonObject().apply {
            addProperty("model", model ?: MODEL_LLM)
            if (jsonSchema != null) {
                add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            }
            add("messages", arr)
        }
        val resp = postJson(url("/chat/completions"), body.toString())
        return parseChatContent(resp)
    }

    override suspend fun generateImage(prompt: String, size: String, model: String?): String {
        requireKey()
        val body = JsonObject().apply {
            addProperty("model", model ?: MODEL_IMAGE)
            addProperty("prompt", prompt)
            addProperty("size", size)
        }
        val resp = postJson(url("/images/generations"), body.toString())
        return parseImageUrl(resp)
    }

    override suspend fun generateVideo(
        prompt: String,
        imageUrl: String?,
        durationMs: Int,
        model: String?,
        onPoll: ((elapsedSec: Int) -> Unit)?
    ): String {
        requireKey()
        // Agnes 视频用 num_frames/frame_rate 控制时长，非 duration。
        // 帧数需满足 8n+1，≤441；24FPS 下 ≤15s（官方建议）。
        val (numFrames, frameRate) = durationToFrames(durationMs)
        val body = JsonObject().apply {
            addProperty("model", model ?: MODEL_VIDEO)
            addProperty("prompt", prompt)
            addProperty("num_frames", numFrames)
            addProperty("frame_rate", frameRate)
            if (!imageUrl.isNullOrBlank()) {
                addProperty("image", imageUrl)
            }
        }
        val submitResp = postJson(url("/videos"), body.toString())
        val taskId = parseVideoTaskId(submitResp)
            ?: throw IllegalStateException("视频任务提交失败：无 task id。响应: $submitResp")

        // 任务可能同步返回结果
        parseVideoUrl(submitResp)?.let { return it }

        // 轮询：优先 /agnesapi，失败降级 /v1/videos/{id}
        var elapsed = 0
        val intervalSec = POLL_INTERVAL_SEC
        while (elapsed < POLL_TIMEOUT_SEC) {
            delay(intervalSec * 1000L)
            elapsed += intervalSec
            onPoll?.invoke(elapsed)
            val statusResp = fetchVideoStatus(taskId)
            val status = parseVideoStatus(statusResp)
            when (status) {
                "succeeded", "completed", "success" -> {
                    return parseVideoUrl(statusResp)
                        ?: throw IllegalStateException("视频任务完成但无 url。响应: $statusResp")
                }
                "failed", "error", "canceled" -> {
                    throw IllegalStateException("视频任务失败: $statusResp")
                }
                // pending/processing/queued 继续轮询
            }
        }
        throw IllegalStateException("视频任务轮询超时（${POLL_TIMEOUT_SEC}s）")
    }

    /** 时长（ms）→ (num_frames, frame_rate)。帧数满足 8n+1 且 ≤441，24FPS ≤15s */
    private fun durationToFrames(durationMs: Int): Pair<Int, Int> {
        val frameRate = 24
        val durationSec = (durationMs / 1000).coerceIn(1, 15)
        val raw = durationSec * frameRate
        val n = (raw + 6) / 8
        val numFrames = (n * 8 + 1).coerceAtMost(441)
        return numFrames to frameRate
    }

    // ===== HTTP =====

    private suspend fun postJson(url: String, json: String): String = exec {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", authHeader())
            .addHeader("Content-Type", "application/json")
            .post(json.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $body")
            }
            body
        }
    }

    private suspend fun getJson(url: String): String = exec {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", authHeader())
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $body")
            }
            body
        }
    }

    // 把 OkHttp 同步调用包成 suspend（在调用方协程上下文，OkHttp 自带线程池）
    private suspend inline fun <T> exec(crossinline block: () -> T): T {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
    }

    /** /agnesapi 挂在根域名（非 /v1 下），剥离 base 的 /v1 后缀 */
    private fun rootPath(path: String): String {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        return if (path.startsWith("/")) "$root$path" else "$root/$path"
    }

    /** 轮询视频状态：优先 /agnesapi?video_id=，失败降级 /v1/videos/{id} */
    private suspend fun fetchVideoStatus(taskId: String): String {
        return runCatching {
            val q = "video_id=" + java.net.URLEncoder.encode(taskId, "UTF-8") +
                "&model_name=" + java.net.URLEncoder.encode(MODEL_VIDEO, "UTF-8")
            getJson(rootPath("/agnesapi?$q"))
        }.getOrElse { getJson(url("/videos/$taskId")) }
    }

    // ===== 解析 =====

    internal fun parseChatContent(resp: String): String {
        val obj = JsonParser.parseString(resp).asJsonObject
        val choices = obj.getAsJsonArray("choices")
        val msg = choices?.get(0)?.asJsonObject?.getAsJsonObject("message")
        return msg?.get("content")?.asString
            ?: throw IllegalStateException("LLM 响应无 content: $resp")
    }

    internal fun parseImageUrl(resp: String): String {
        val obj = JsonParser.parseString(resp).asJsonObject
        val data = obj.getAsJsonArray("data")?.get(0)?.asJsonObject
        val url = data?.get("url")?.asString
            ?: data?.get("b64_json")?.asString?.let { "data:image/png;base64,$it" }
        return url ?: throw IllegalStateException("图片响应无 url: $resp")
    }

    internal fun parseVideoTaskId(resp: String): String? {
        val obj = runCatching { JsonParser.parseString(resp).asJsonObject }.getOrNull() ?: return null
        // 官方 CLI 优先级：video_id > task_id > id
        return obj.get("video_id")?.asString
            ?: obj.get("task_id")?.asString
            ?: obj.get("id")?.asString
            ?: obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.asString
    }

    internal fun parseVideoStatus(resp: String): String? {
        val obj = runCatching { JsonParser.parseString(resp).asJsonObject }.getOrNull() ?: return null
        return obj.get("status")?.asString?.lowercase()
            ?: obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("status")?.asString?.lowercase()
    }

    internal fun parseVideoUrl(resp: String): String? {
        val obj = runCatching { JsonParser.parseString(resp).asJsonObject }.getOrNull() ?: return null
        // 官方 CLI：优先 url，次选 remixed_from_video_id
        obj.get("url")?.asString?.let { return it }
        obj.get("remixed_from_video_id")?.asString?.let { return it }
        obj.get("video_url")?.asString?.let { return it }
        obj.get("video")?.asString?.let { return it }
        obj.get("output_url")?.asString?.let { return it }
        obj.getAsJsonArray("data")?.get(0)?.asJsonObject?.get("url")?.asString?.let { return it }
        obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("url")?.asString?.let { return it }
        return null
    }

    companion object {
        const val MODEL_LLM = "agnes-2.0-flash"
        const val MODEL_IMAGE = "agnes-image-2.1-flash"
        const val MODEL_VIDEO = "agnes-video-v2.0"
        const val POLL_INTERVAL_SEC = 5
        const val POLL_TIMEOUT_SEC = 300
    }
}
