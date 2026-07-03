package io.legado.app.video.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.content.FileProvider
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.VideoJob
import io.legado.app.databinding.ActivityVideoGenBinding
import io.legado.app.service.VideoGenService
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.video.VideoGenEngine
import java.io.File

/**
 * 视频生成进度页：实时展示状态/进度，完成后预览并支持分享。
 *
 * 启动参数：jobId
 */
class VideoGenActivity : BaseActivity<ActivityVideoGenBinding>() {

    override val binding by viewBinding(ActivityVideoGenBinding::inflate)

    private var jobId: Long = 0L
    private var job: VideoJob? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        jobId = intent.getLongExtra("jobId", 0L)
        if (jobId <= 0) {
            finish()
            return
        }
        binding.titleBar.setTitle(R.string.video_gen)
        loadJob()
        binding.tvCancel.setOnClickListener {
            sendCancel()
        }
        binding.tvPlay.setOnClickListener {
            job?.outputPath?.let { playVideo(it) }
        }
        binding.tvShare.setOnClickListener {
            job?.outputPath?.let { shareVideo(it) }
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<VideoGenEngine.Progress>(EventBus.VIDEO_GEN_PROGRESS) { progress ->
            if (progress?.jobId == jobId) renderProgress(progress)
        }
        observeEvent<VideoGenEngine.Progress>(EventBus.VIDEO_GEN_STATE) { progress ->
            if (progress?.jobId == jobId) {
                loadJob()
                renderProgress(progress)
            }
        }
    }

    private fun loadJob() {
        job = appDb.videoJobDao.get(jobId)?.also { renderJob(it) }
    }

    private fun renderJob(job: VideoJob) {
        binding.tvBookName.text = job.bookName
        binding.tvChapterRange.text = job.chapterRange
    }

    private fun renderProgress(progress: VideoGenEngine.Progress) {
        loadJob()
        val current = job ?: return
        binding.pbProgress.progress = progress.progress
        binding.tvProgressText.text = progress.message ?: statusText(current.status)
        when (current.status) {
            VideoJob.STATUS_DONE -> showDone(current)
            VideoJob.STATUS_FAILED -> {
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = current.errorMsg ?: getString(R.string.video_gen_failed)
                binding.pbProgress.visibility = View.GONE
                binding.tvCancel.visibility = View.GONE
            }
            VideoJob.STATUS_CANCELED -> {
                binding.tvProgressText.text = getString(R.string.video_gen_canceled)
                binding.pbProgress.visibility = View.GONE
                binding.tvCancel.visibility = View.GONE
            }
            else -> {
                binding.tvError.visibility = View.GONE
            }
        }
    }

    private fun showDone(job: VideoJob) {
        val path = job.outputPath ?: return
        binding.pbProgress.visibility = View.GONE
        binding.tvProgressText.text = getString(R.string.video_gen_done)
        binding.tvCancel.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        binding.llActions.visibility = View.VISIBLE
        playVideo(path)
    }

    private fun playVideo(path: String) {
        val uri = uriForPath(path)
        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnPreparedListener { it.isLooping = false }
        binding.videoView.start()
    }

    private fun shareVideo(path: String) {
        val uri = uriForPath(path)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.video_gen_share)))
    }

    private fun uriForPath(path: String): Uri {
        val file = File(path)
        return if (file.absolutePath.startsWith("/storage") || file.absolutePath.startsWith("/data")) {
            FileProvider.getUriForFile(this, "${packageName}.fileProvider", file)
        } else {
            Uri.parse(path)
        }
    }

    private fun sendCancel() {
        val intent = Intent(this, VideoGenService::class.java).apply {
            action = IntentAction.videoGenStop
        }
        startService(intent)
        toastOnUi(R.string.video_gen_canceled)
    }

    private fun statusText(status: Int): String = when (status) {
        VideoJob.STATUS_PENDING -> getString(R.string.video_gen_status_pending)
        VideoJob.STATUS_STORYBOARD -> getString(R.string.video_gen_status_storyboard)
        VideoJob.STATUS_EXECUTE -> getString(R.string.video_gen_status_execute)
        VideoJob.STATUS_COMPOSE -> getString(R.string.video_gen_status_compose)
        VideoJob.STATUS_DONE -> getString(R.string.video_gen_status_done)
        VideoJob.STATUS_FAILED -> getString(R.string.video_gen_status_failed)
        VideoJob.STATUS_CANCELED -> getString(R.string.video_gen_status_canceled)
        else -> getString(R.string.video_gen_running)
    }
}
