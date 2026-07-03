package io.legado.app.video.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShotExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 一个完全可控的 fake provider：图片返回 data: URI（base64），视频返回 data: URI
    // 这样 download() 走 base64 分支，无需真实网络
    private class FakeProvider(
        val imageCalls: MutableList<String> = mutableListOf(),
        val videoCalls: MutableList<String> = mutableListOf(),
        var failVideoNTimes: Int = 0
    ) : io.legado.app.video.provider.VideoGenProvider {
        override val id = "fake"
        override suspend fun chatLLM(
            messages: List<io.legado.app.video.provider.VideoGenProvider.Msg>,
            jsonSchema: String?,
            model: String?
        ) = ""

        override suspend fun generateImage(prompt: String, size: String, model: String?): String {
            imageCalls.add(prompt)
            // 1x1 png base64
            return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        }

        override suspend fun generateVideo(
            prompt: String,
            imageUrl: String?,
            durationMs: Int,
            model: String?,
            onPoll: ((Int) -> Unit)?
        ): String {
            videoCalls.add(prompt)
            if (failVideoNTimes > 0) {
                failVideoNTimes--
                throw RuntimeException("fake video fail")
            }
            // 极简 mp4 不是真视频，但 download 只存字节，compose 才需真视频
            return "data:video/mp4;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28y"
        }
    }

    @Test
    fun `已有 videoPath 的镜头被跳过`() {
        val provider = FakeProvider()
        val workDir = tmp.newFolder("work1")
        val storyboard = Storyboard("t", listOf(
            Shot(index = 0, videoPath = "/tmp/exists.mp4", status = Shot.STATUS_OK)
        ))
        kotlinx.coroutines.runBlocking {
            ShotExecutor(provider, "1024x576", 2, workDir).execute(storyboard)
        }
        // 不应调用生成
        assertEquals(0, provider.imageCalls.size)
        assertEquals(0, provider.videoCalls.size)
    }

    @Test
    fun `正常生成单镜头并下载产物`() {
        val provider = FakeProvider()
        val workDir = tmp.newFolder("work2")
        val storyboard = Storyboard("t", listOf(
            Shot(index = 0, imagePrompt = "a cat", motionHint = "推进", durationMs = 5000)
        ))
        kotlinx.coroutines.runBlocking {
            ShotExecutor(provider, "1024x576", 2, workDir).execute(storyboard)
        }
        val shot = storyboard.shots[0]
        assertEquals(Shot.STATUS_OK, shot.status)
        assertEquals("a cat", provider.imageCalls[0])
        assertEquals("推进", provider.videoCalls[0])
        assertTrue(File(shot.imagePath).exists())
        assertTrue(File(shot.videoPath).exists())
    }

    @Test
    fun `video 生成失败重试后成功`() {
        val provider = FakeProvider(failVideoNTimes = 2)
        val workDir = tmp.newFolder("work3")
        val storyboard = Storyboard("t", listOf(
            Shot(index = 0, imagePrompt = "a", motionHint = "p", durationMs = 5000)
        ))
        kotlinx.coroutines.runBlocking {
            ShotExecutor(provider, "1024x576", 2, workDir).execute(storyboard)
        }
        assertEquals(Shot.STATUS_OK, storyboard.shots[0].status)
        // video 被调用 3 次（2 失败 + 1 成功）
        assertEquals(3, provider.videoCalls.size)
    }

    @Test
    fun `video 生成全部失败则镜头标记 failed 但不抛`() {
        val provider = FakeProvider(failVideoNTimes = 10)
        val workDir = tmp.newFolder("work4")
        val storyboard = Storyboard("t", listOf(
            Shot(index = 0, imagePrompt = "a", motionHint = "p", durationMs = 5000)
        ))
        kotlinx.coroutines.runBlocking {
            ShotExecutor(provider, "1024x576", 2, workDir).execute(storyboard)
        }
        assertEquals(Shot.STATUS_FAILED, storyboard.shots[0].status)
        assertNotNull(storyboard.shots[0].errorMsg)
    }
}
