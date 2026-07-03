package io.legado.app.video.pipeline

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportResult
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
 * 统一缩放到目标分辨率，顺序衔接音轨（不混音）。
 * 转场用最简方案：依赖片段本身顺序，不做额外转场特效（YAGNI）。
 */
@UnstableApi
class VideoComposer(
    private val context: Context
) {

    /**
     * @param shots 已生成视频的镜头（按 index 顺序取 videoPath）
     * @param outputPath 输出 MP4 路径
     * @param width 输出宽度（高度按 16:9 推算），默认 1920x1080
     * @param onProgress 0..1f
     */
    suspend fun compose(
        shots: List<Shot>,
        outputPath: String,
        width: Int = 1920,
        onProgress: ((Float) -> Unit)? = null
    ): String {
        val output = File(outputPath)
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val validShots = shots.filter { !it.videoPath.isNullOrBlank() && File(it.videoPath).exists() }
        if (validShots.isEmpty()) throw IllegalStateException("无可用镜头视频片段")

        // 统一分辨率 Effect
        val height = (width * 9 / 16)
        val presentation = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)

        val sequence = EditedMediaItemSequence(
            validShots.map { shot ->
                val mediaItem = MediaItem.fromUri(File(shot.videoPath!!).toURI())
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(
                        androidx.media3.common.Effects(
                            emptyList(),
                            listOf(presentation)
                        )
                    )
                    .build()
            }
        )

        val composition = Composition.Builder(sequence).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
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
