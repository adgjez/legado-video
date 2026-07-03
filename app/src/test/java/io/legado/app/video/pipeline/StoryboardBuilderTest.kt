package io.legado.app.video.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoryboardBuilderTest {

    private val fakeProvider = object : io.legado.app.video.provider.VideoGenProvider {
        override val id = "fake"
        var nextResp = ""
        override suspend fun chatLLM(
            messages: List<io.legado.app.video.provider.VideoGenProvider.Msg>,
            jsonSchema: String?,
            model: String?
        ) = nextResp

        override suspend fun generateImage(prompt: String, size: String, model: String?) = ""
        override suspend fun generateVideo(
            prompt: String,
            imageUrl: String?,
            durationMs: Int,
            model: String?,
            onPoll: ((Int) -> Unit)?
        ) = ""
    }
    private val builder = StoryboardBuilder(fakeProvider)

    @Test
    fun `stripCodeFence 去掉 json 代码块`() {
        val raw = "```json\n{\"a\":1}\n```"
        assertEquals("{\"a\":1}", builder.stripCodeFence(raw).trim())
    }

    @Test
    fun `stripCodeFence 无代码块原样返回`() {
        assertEquals("{\"a\":1}", builder.stripCodeFence("{\"a\":1}").trim())
    }

    @Test
    fun `parse 合法分镜表`() {
        val json = """
            {"title":"t","shots":[
              {"index":0,"imagePrompt":"a","motionHint":"推进","durationMs":5000,"subtitle":"雨"},
              {"index":1,"imagePrompt":"b","motionHint":"拉远","durationMs":4000,"subtitle":"夜"}
            ]}
        """.trimIndent()
        val sb = builder.parse(json)
        assertNotNull(sb)
        assertEquals("t", sb!!.title)
        assertEquals(2, sb.shots.size)
        assertEquals(0, sb.shots[0].index)
        assertEquals(1, sb.shots[1].index)
    }

    @Test
    fun `parse 空镜头返回 null`() {
        assertNull(builder.parse("""{"title":"t","shots":[]}"""))
    }

    @Test
    fun `parse 非法 json 返回 null`() {
        assertNull(builder.parse("not json"))
        assertNull(builder.parse(""))
    }

    @Test
    fun `parse 带代码块也能解析`() {
        val raw = "```json\n{\"title\":\"t\",\"shots\":[{\"index\":0,\"imagePrompt\":\"a\",\"motionHint\":\"\",\"durationMs\":5000,\"subtitle\":\"\"}]}\n```"
        assertNotNull(builder.parse(raw))
    }

    @Test
    fun `build 解析失败时重试并抛异常`() {
        fakeProvider.nextResp = "not json"
        kotlinx.coroutines.runBlocking {
            try {
                builder.build("content", 30)
                fail("应抛异常")
            } catch (e: Exception) {
                assertTrue(e is Exception)
            }
        }
    }

    @Test
    fun `build 成功返回分镜表`() {
        fakeProvider.nextResp = """{"title":"t","shots":[{"index":0,"imagePrompt":"a","motionHint":"p","durationMs":5000,"subtitle":"s"}]}"""
        kotlinx.coroutines.runBlocking {
            val sb = builder.build("content", 30)
            assertEquals(1, sb.shots.size)
        }
    }
}
