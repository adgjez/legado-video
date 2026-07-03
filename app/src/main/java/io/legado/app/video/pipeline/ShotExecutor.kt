package io.legado.app.video.pipeline

import io.legado.app.constant.AppLog
import io.legado.app.video.provider.VideoGenProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * 按分镜表并行执行每镜头两步生成：图 → 图生视频。
 *
 * - Semaphore 限并发
 * - 每镜头指数退避重试 maxRetries 次
 * - 断点续传：跳过已有 videoPath 的镜头
 * - 单镜头失败可跳过（不阻塞整体）
 * - 远端 URL 资源下载到本地 workDir
 *
 * @param onProgress 完成 N 个镜头时的回调
 */
class ShotExecutor(
    private val provider: VideoGenProvider,
    private val imageSize: String,
    private val concurrency: Int,
    private val workDir: File
) {

    suspend fun execute(
        storyboard: Storyboard,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Storyboard {
        if (!workDir.exists()) workDir.mkdirs()
        val total = storyboard.shots.size
        val sem = Semaphore(concurrency.coerceAtLeast(1))
        val doneCount = java.util.concurrent.atomic.AtomicInteger(storyboard.doneCount)

        coroutineScope {
            storyboard.shots.forEach { shot ->
                // 断点续传：已有视频则跳过
                if (!shot.videoPath.isNullOrBlank() && File(shot.videoPath).exists()) {
                    if (shot.status != Shot.STATUS_OK) shot.status = Shot.STATUS_OK
                    return@forEach
                }
                launch {
                    sem.withPermit {
                        runCatching { executeShot(shot) }
                            .onFailure { e ->
                                shot.status = Shot.STATUS_FAILED
                                shot.errorMsg = e.message
                                AppLog.put("镜头 ${shot.index} 失败：${e.message}", e)
                            }
                            .onSuccess {
                                doneCount.incrementAndGet()
                                onProgress?.invoke(doneCount.get(), total)
                            }
                    }
                }
            }
        }
        return storyboard
    }

    /** 单镜头：图生图 → 图生视频 → 下载。失败重试 */
    private suspend fun executeShot(shot: Shot) {
        var lastErr: Throwable? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                // 1. 文生图（若已有 imageUrl 且未失效则复用）
                if (shot.imageUrl.isNullOrBlank()) {
                    shot.imageUrl = provider.generateImage(shot.imagePrompt, imageSize)
                }
                // 2. 下载图片
                val imgFile = File(workDir, "shot_${shot.index}.png")
                if (shot.imagePath.isNullOrBlank() || !File(shot.imagePath).exists()) {
                    download(shot.imageUrl!!, imgFile)
                    shot.imagePath = imgFile.absolutePath
                }
                // 3. 图生视频（异步轮询）
                if (shot.videoUrl.isNullOrBlank()) {
                    shot.videoUrl = provider.generateVideo(
                        prompt = shot.motionHint,
                        imageUrl = shot.imageUrl,
                        durationMs = shot.durationMs
                    )
                }
                // 4. 下载视频
                val vidFile = File(workDir, "shot_${shot.index}.mp4")
                download(shot.videoUrl!!, vidFile)
                shot.videoPath = vidFile.absolutePath
                shot.status = Shot.STATUS_OK
                shot.errorMsg = null
                return
            } catch (e: Exception) {
                lastErr = e
                backoff(attempt)
            }
        }
        throw lastErr ?: IllegalStateException("镜头 ${shot.index} 生成失败")
    }

    /** 下载远端 URL 到本地文件。data: URI 直接 base64 解码 */
    internal suspend fun download(url: String, dest: File) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (url.startsWith("data:")) {
                val comma = url.indexOf(',')
                val b64 = url.substring(comma + 1)
                dest.writeBytes(java.util.Base64.getDecoder().decode(b64))
            } else {
                val req = okhttp3.Request.Builder().url(url).get().build()
                io.legado.app.help.http.okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP ${resp.code}: $url")
                    resp.body?.byteStream()?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("下载体为空: $url")
                }
            }
        }
    }

    private suspend fun backoff(attempt: Int) {
        val ms = min(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS)
        kotlinx.coroutines.delay(ms)
    }

    companion object {
        const val MAX_RETRIES = 3
        const val BASE_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 16000L
    }
}
