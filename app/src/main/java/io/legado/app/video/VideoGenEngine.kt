package io.legado.app.video

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.VideoJob
import io.legado.app.utils.postEvent
import io.legado.app.video.pipeline.ShotExecutor
import io.legado.app.video.pipeline.StoryboardBuilder
import io.legado.app.video.pipeline.VideoComposer
import io.legado.app.video.provider.ProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 视频生成门面。编排：分镜表 → 并行执行 → 合成。
 *
 * 持有每个 job 的协程，支持取消。
 * 状态/进度写回 [VideoJob]，并通过 [EventBus] 通知 UI。
 */
object VideoGenEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = mutableMapOf<Long, Job>()

    /** 生成进度事件 payload */
    data class Progress(
        val jobId: Long,
        val status: Int,
        val progress: Int,
        val message: String? = null
    )

    val isRunning: Boolean get() = runningJobs.values.any { it.isActive }

    fun start(context: Context, job: VideoJob, content: String, styleHint: String? = null) {
        if (runningJobs[job.id]?.isActive == true) return
        val workDir = File(context.cacheDir, "videoGen/${job.id}").apply { mkdirs() }
        val outputDir = context.getExternalFilesDir("video")?.apply { mkdirs() }
            ?: File(context.filesDir, "video").apply { mkdirs() }
        val outputPath = File(outputDir, "${job.id}.mp4").absolutePath

        val corJob = scope.launch {
            try {
                val provider = ProviderConfig.create()
                updateJob(job, VideoJob.STATUS_STORYBOARD, 5) { it.errorMsg = null }
                // 1. 分镜表
                val storyboard = StoryboardBuilder(provider).build(
                    content = content,
                    targetDurationSec = ProviderConfig.targetDurationSec,
                    styleHint = styleHint
                )
                job.storyboardJson = storyboard.toJson()
                appDb.videoJobDao.update(job)

                // 2. 并行执行镜头
                updateJob(job, VideoJob.STATUS_EXECUTE, 10)
                ShotExecutor(
                    provider = provider,
                    imageSize = ProviderConfig.imageSize,
                    concurrency = ProviderConfig.concurrency,
                    workDir = workDir
                ).execute(storyboard) { done, total ->
                    val pct = 10 + (done * 70 / total.coerceAtLeast(1))
                    job.storyboardJson = storyboard.toJson()
                    appDb.videoJobDao.update(job)
                    postProgress(job.id, VideoJob.STATUS_EXECUTE, pct, "镜头 $done/$total")
                }
                job.storyboardJson = storyboard.toJson()
                appDb.videoJobDao.update(job)

                // 3. 合成
                updateJob(job, VideoJob.STATUS_COMPOSE, 85)
                VideoComposer(context).compose(
                    shots = storyboard.shots,
                    outputPath = outputPath
                ) { frac ->
                    val pct = 85 + (frac * 15).toInt()
                    postProgress(job.id, VideoJob.STATUS_COMPOSE, pct, null)
                }
                job.outputPath = outputPath
                updateJob(job, VideoJob.STATUS_DONE, 100)
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateJob(job, VideoJob.STATUS_CANCELED, job.progress)
                throw e
            } catch (e: Exception) {
                AppLog.put("视频生成失败: ${e.message}", e)
                job.errorMsg = e.message
                updateJob(job, VideoJob.STATUS_FAILED, job.progress)
            } finally {
                runningJobs.remove(job.id)
            }
        }
        runningJobs[job.id] = corJob
    }

    fun cancel(jobId: Long) {
        runningJobs[jobId]?.cancel()
    }

    private fun updateJob(job: VideoJob, status: Int, progress: Int, block: ((VideoJob) -> Unit)? = null) {
        job.status = status
        job.progress = progress
        block?.invoke(job)
        runCatching { appDb.videoJobDao.update(job) }
        postProgress(job.id, status, progress, null)
    }

    private fun postProgress(jobId: Long, status: Int, progress: Int, message: String?) {
        postEvent(EventBus.VIDEO_GEN_PROGRESS, Progress(jobId, status, progress, message))
        if (status == VideoJob.STATUS_DONE || status == VideoJob.STATUS_FAILED || status == VideoJob.STATUS_CANCELED) {
            postEvent(EventBus.VIDEO_GEN_STATE, Progress(jobId, status, progress, message))
        }
    }
}
