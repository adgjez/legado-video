package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.VideoJob
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.observeEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.video.VideoGenEngine
import io.legado.app.video.VideoGenInput
import io.legado.app.video.ui.VideoGenActivity
import splitties.systemservices.notificationManager

/**
 * 视频生成前台服务。长任务 + 进度通知。
 *
 * 启动方式：
 *   intent.action = IntentAction.videoGenStart
 *   intent.putExtra("jobId", jobId)
 */
class VideoGenService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private var currentJobId: Long = -1L

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.video_gen))
            .setContentIntent(activityPendingIntent<VideoGenActivity>("activity"))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<VideoGenService>(IntentAction.videoGenStop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        observeEvent<VideoGenEngine.Progress>(EventBus.VIDEO_GEN_PROGRESS) { progress ->
            if (progress.jobId == currentJobId) upNotification(progress)
        }
        observeEvent<VideoGenEngine.Progress>(EventBus.VIDEO_GEN_STATE) { progress ->
            if (progress?.jobId == currentJobId && progress.status != VideoJob.STATUS_EXECUTE
                && progress.status != VideoJob.STATUS_STORYBOARD
                && progress.status != VideoJob.STATUS_COMPOSE
            ) {
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.videoGenStart -> startGen(intent.getLongExtra("jobId", -1L))
                IntentAction.videoGenStop -> {
                    if (currentJobId > 0) VideoGenEngine.cancel(currentJobId)
                    stopSelf()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startGen(jobId: Long) {
        if (jobId <= 0) {
            stopSelf()
            return
        }
        currentJobId = jobId
        val job = appDb.videoJobDao.get(jobId)
        if (job == null) {
            AppLog.put("视频生成：任务不存在 $jobId")
            stopSelf()
            return
        }
        val input = VideoGenInput.take(jobId)
        if (input == null) {
            AppLog.put("视频生成：输入内容缺失 $jobId")
            job.errorMsg = "输入内容缺失"
            job.status = VideoJob.STATUS_FAILED
            appDb.videoJobDao.update(job)
            stopSelf()
            return
        }
        upNotification(VideoGenEngine.Progress(jobId, VideoJob.STATUS_STORYBOARD, 0, getString(R.string.video_gen_running)))
        VideoGenEngine.start(this, job, input.first, input.second)
    }

    private fun upNotification(progress: VideoGenEngine.Progress) {
        val content = progress.message ?: when (progress.status) {
            VideoJob.STATUS_STORYBOARD -> getString(R.string.video_gen_progress_storyboard)
            VideoJob.STATUS_EXECUTE -> getString(R.string.video_gen_running)
            VideoJob.STATUS_COMPOSE -> getString(R.string.video_gen_progress_compose)
            VideoJob.STATUS_DONE -> getString(R.string.video_gen_done)
            VideoJob.STATUS_FAILED -> getString(R.string.video_gen_failed)
            VideoJob.STATUS_CANCELED -> getString(R.string.video_gen_canceled)
            else -> getString(R.string.video_gen_running)
        }
        val indeterminate = progress.status == VideoJob.STATUS_STORYBOARD || progress.status == VideoJob.STATUS_COMPOSE
        notificationBuilder.setProgress(100, progress.progress, indeterminate)
        notificationBuilder.setContentText(content)
        notificationManager.notify(NotificationId.VideoGenService, notificationBuilder.build())
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(getString(R.string.video_gen_running))
        notificationBuilder.setProgress(100, 0, true)
        startForeground(NotificationId.VideoGenService, notificationBuilder.build())
    }

    override fun onDestroy() {
        isRun = false
        if (currentJobId > 0) VideoGenEngine.cancel(currentJobId)
        VideoGenInput.clear(currentJobId)
        super.onDestroy()
    }
}
