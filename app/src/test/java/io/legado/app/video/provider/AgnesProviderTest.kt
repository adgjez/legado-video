package io.legado.app.video.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AgnesProvider 解析逻辑单测（纯 JVM，无 HTTP 依赖）。
 */
class AgnesProviderTest {

    private val provider = AgnesProvider("https://example.com/v1", "test-key")

    @Test
    fun `parseChatContent 提取 choices message content`() {
        val resp = """
            {"choices":[{"message":{"role":"assistant","content":"hello"}}]}
        """.trimIndent()
        assertEquals("hello", provider.parseChatContent(resp))
    }

    @Test
    fun `parseChatContent 无 content 抛异常`() {
        val resp = """{"choices":[]}"""
        assertThrows(IllegalStateException::class.java) {
            provider.parseChatContent(resp)
        }
    }

    @Test
    fun `parseImageUrl 提取 data url`() {
        val resp = """{"data":[{"url":"https://cdn/img/1.png"}]}"""
        assertEquals("https://cdn/img/1.png", provider.parseImageUrl(resp))
    }

    @Test
    fun `parseImageUrl b64_json 转 data uri`() {
        val resp = """{"data":[{"b64_json":"QUJD"}]}"""
        assertEquals("data:image/png;base64,QUJD", provider.parseImageUrl(resp))
    }

    @Test
    fun `parseVideoTaskId 支持 id 与 task_id`() {
        assertEquals("t1", provider.parseVideoTaskId("""{"id":"t1"}"""))
        assertEquals("t2", provider.parseVideoTaskId("""{"task_id":"t2"}"""))
        assertEquals("t3", provider.parseVideoTaskId("""{"data":{"id":"t3"}}"""))
    }

    @Test
    fun `parseVideoTaskId 无 id 返回 null`() {
        assertNull(provider.parseVideoTaskId("""{"foo":"bar"}"""))
        assertNull(provider.parseVideoTaskId("not json"))
    }

    @Test
    fun `parseVideoStatus 大小写归一`() {
        assertEquals("processing", provider.parseVideoStatus("""{"status":"Processing"}"""))
        assertEquals("succeeded", provider.parseVideoStatus("""{"status":"SUCCEEDED"}"""))
        assertNull(provider.parseVideoStatus("not json"))
    }

    @Test
    fun `parseVideoUrl 支持多种字段名`() {
        assertEquals("u1", provider.parseVideoUrl("""{"url":"u1"}"""))
        assertEquals("u2", provider.parseVideoUrl("""{"video":"u2"}"""))
        assertEquals("u3", provider.parseVideoUrl("""{"output_url":"u3"}"""))
        assertEquals("u4", provider.parseVideoUrl("""{"data":[{"url":"u4"}]}"""))
        assertEquals("u5", provider.parseVideoUrl("""{"data":{"url":"u5"}}"""))
    }

    @Test
    fun `parseVideoUrl 无匹配返回 null`() {
        assertNull(provider.parseVideoUrl("""{"foo":"bar"}"""))
    }

    @Test
    fun `requireKey 空 key 抛异常`() {
        val p = AgnesProvider("https://example.com/v1", "")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                p.chatLLM(listOf(VideoGenProvider.Msg.user("hi")))
            }
        }
    }
}
