package io.legado.app.video.pipeline

import io.legado.app.video.provider.VideoGenProvider

/**
 * 调 LLM 产出结构化分镜表。
 *
 * 单次调用，失败重试 maxRetries 次；解析失败用更严格 schema 兜底重试 1 次。
 */
class StoryboardBuilder(
    private val provider: VideoGenProvider
) {

    /**
     * @param content 章节正文
     * @param targetDurationSec 目标总时长（秒），LLM 据此决定镜头数
     * @param styleHint 可选风格提示（如"赛博朋克"/"水墨"）
     */
    suspend fun build(
        content: String,
        targetDurationSec: Int,
        styleHint: String? = null
    ): Storyboard {
        val schema = """
            {"title":"string","shots":[{"index":int,"imagePrompt":"string","motionHint":"string","durationMs":int,"subtitle":"string"}]}
        """.trimIndent()

        val systemPrompt = buildString {
            append("你是短视频分镜师。根据给定小说文本，产出 ${targetDurationSec} 秒左右的分镜表。")
            append("镜头数 = 目标时长 / 单镜头时长(默认5秒)，向上取整。")
            if (!styleHint.isNullOrBlank()) append("统一视觉风格：$styleHint。")
            append("imagePrompt 用英文写画面描述，含场景/主体/光线/景别；motionHint 用中文写运镜；subtitle 用中文，≤8字。")
            append("严格输出 JSON，无 markdown 代码块。")
        }

        var lastErr: Throwable? = null
        // 正常重试
        repeat(MAX_RETRIES) { attempt ->
            try {
                val resp = provider.chatLLM(
                    messages = listOf(
                        VideoGenProvider.Msg.system(systemPrompt),
                        VideoGenProvider.Msg.user("文本：\n$content")
                    ),
                    jsonSchema = schema
                )
                parse(resp)?.let { return it }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        // 严格 schema 兜底
        try {
            val resp = provider.chatLLM(
                messages = listOf(
                    VideoGenProvider.Msg.system("$systemPrompt\n只输出纯 JSON 对象，首字符必须是 { ，末字符必须是 } ，禁止任何额外文字。"),
                    VideoGenProvider.Msg.user("文本：\n$content")
                ),
                jsonSchema = schema
            )
            parse(resp)?.let { return it }
        } catch (e: Exception) {
            lastErr = e
        }
        throw lastErr ?: IllegalStateException("分镜表生成失败：无法解析 LLM 输出")
    }

    /** 从 LLM 输出解析分镜表，失败返回 null */
    internal fun parse(resp: String): Storyboard? {
        val cleaned = stripCodeFence(resp).trim()
        if (cleaned.isEmpty()) return null
        val sb = Storyboard.fromJson(cleaned).getOrNull() ?: return null
        if (sb.shots.isEmpty()) return null
        // 规整 index
        sb.shots.forEachIndexed { i, s -> s.index = i }
        return sb
    }

    /** 去掉可能的 ```json ... ``` 包裹 */
    internal fun stripCodeFence(resp: String): String {
        val s = resp.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline > 0) {
                val body = s.substring(firstNewline + 1)
                val end = body.lastIndexOf("```")
                return if (end >= 0) body.substring(0, end) else body
            }
        }
        return s
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
