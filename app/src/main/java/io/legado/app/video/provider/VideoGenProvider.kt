package io.legado.app.video.provider

/**
 * 视频生成 AI Provider 抽象。
 *
 * 可插拔：首发 Agnes，后续可加豆包/通义/OpenAI 兼容。
 * 所有方法均为 suspend，由调用方在协程中调用。
 */
interface VideoGenProvider {

    val id: String

    /**
     * LLM 对话。返回 assistant 文本。
     * @param messages 消息列表
     * @param jsonSchema 可选，约束输出 JSON 结构（用 system prompt 拼接实现）
     * @param model 指定模型，null 用默认
     */
    suspend fun chatLLM(
        messages: List<Msg>,
        jsonSchema: String? = null,
        model: String? = null
    ): String

    /**
     * 文生图。返回图片 URL（远端）。
     * @param prompt 画面描述
     * @param size 形如 "1024x576"
     */
    suspend fun generateImage(prompt: String, size: String, model: String? = null): String

    /**
     * 生成视频。返回视频 URL（远端）。
     * 内部封装异步任务：提交 → 轮询 → 取结果。
     * @param prompt 运镜/动作描述
     * @param imageUrl 关键帧图片 URL，非空表示图生视频；空表示文生视频
     * @param durationMs 目标时长（毫秒）
     * @param onPoll 可选轮询回调，参数为已等待秒数，用于进度展示
     */
    suspend fun generateVideo(
        prompt: String,
        imageUrl: String?,
        durationMs: Int,
        model: String? = null,
        onPoll: ((elapsedSec: Int) -> Unit)? = null
    ): String

    /** OpenAI 兼容消息 */
    data class Msg(
        val role: String,        // "system" / "user" / "assistant"
        val content: String
    ) {
        companion object {
            fun system(content: String) = Msg("system", content)
            fun user(content: String) = Msg("user", content)
        }
    }
}
