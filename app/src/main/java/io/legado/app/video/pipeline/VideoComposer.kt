package io.legado.app.video.pipeline

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import io.legado.app.constant.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用 Media3 Transformer 把多个镜头视频片段按序拼接成单个 MP4。
 *
 * 顺序衔接音轨（不混音），不统一分辨率（图生视频步骤已按配置尺寸产出）。
 * 转场用最简方案：依赖片段本身顺序，不做额外转场特效（YAGNI）。
 */
@UnstableApi
class VideoComposer(
    private val context: Context
) {

    /**
     * @param shots 已生成视频的镜头（按 index 顺序取 videoPath）
     * @param outputPath 输出 MP4 路径
     * @param onProgress 0..1f
     */
    suspend fun compose(
        shots: List<Shot>,
        outputPath: String,
        onProgress: ((Float) -> Unit)? = null
    ): String {
        val output = File(outputPath)
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val validShots = shots.filter { !it.videoPath.isNullOrBlank() && File(it.videoPath).exists() }
        if (validShots.isEmpty()) throw IllegalStateException("无可用镜头视频片段")

        val sequence = EditedMediaItemSequence(
            validShots.map { shot ->
                val mediaItem = MediaItem.fromUri(Uri.fromFile(File(shot.videoPath!!)))
                EditedMediaItem.Builder(mediaItem).build()
            }
        )

        val composition = Composition.Builder(sequence).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .build()

        withTimeout(COMPOSE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(outputPath)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        AppLog.put("视频合成失败: ${exception.message}", exception)
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                }
                transformer.addListener(listener)
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                transformer.start(composition, outputPath)
            }
        }
        return outputPath
    }

    companion object {
        const val COMPOSE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
