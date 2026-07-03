package io.legado.app.video

import java.util.concurrent.ConcurrentHashMap

/**
 * 大文本内容传递 holder。
 *
 * 章节正文可能很大，超出 Intent binder 事务限制（~1MB），
 * 因此由入口（Dialog/Activity）采集正文后 put，Service 启动后 take。
 */
object VideoGenInput {

    private data class Input(val content: String, val styleHint: String?)

    private val map = ConcurrentHashMap<Long, Input>()

    fun put(jobId: Long, content: String, styleHint: String? = null) {
        map[jobId] = Input(content, styleHint)
    }

    fun take(jobId: Long): Pair<String, String?>? {
        val input = map.remove(jobId) ?: return null
        return input.content to input.styleHint
    }

    fun clear(jobId: Long) {
        map.remove(jobId)
    }
}
