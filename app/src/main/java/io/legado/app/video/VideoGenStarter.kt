package io.legado.app.video

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.VideoJob
import io.legado.app.help.config.AppConfig
import io.legado.app.service.VideoGenService
import io.legado.app.utils.startActivity
import io.legado.app.video.ui.VideoGenActivity

/**
 * 视频生成启动入口：创建任务、存入正文、启动前台服务、跳转进度页。
 *
 * 用于阅读页与书籍详情页两个入口的统一调用。
 */
object VideoGenStarter {

    /**
     * @param context 上下文
     * @param bookUrl 书籍 url
     * @param bookName 书名
     * @param chapterRange 章节范围标签（如 "ch12" / "ch1-ch3" / "selection"）
     * @param content 正文文本
     * @param styleHint 风格提示（可选）
     * @return 新建的 jobId
     */
    fun start(
        context: Context,
        bookUrl: String,
        bookName: String,
        chapterRange: String,
        content: String,
        styleHint: String? = null
    ): Long {
        val job = VideoJob(
            bookUrl = bookUrl,
            bookName = bookName,
            chapterRange = chapterRange,
            provider = AppConfig.videoGenProvider.ifBlank { "agnes" }
        )
        appDb.videoJobDao.insert(job)
        VideoGenInput.put(job.id, content, styleHint)
        val intent = Intent(context, VideoGenService::class.java).apply {
            action = IntentAction.videoGenStart
            putExtra("jobId", job.id)
        }
        ContextCompat.startForegroundService(context, intent)
        launchProgress(context, job.id)
        return job.id
    }

    /**
     * 跳转进度页。
     */
    fun launchProgress(context: Context, jobId: Long) {
        context.startActivity<VideoGenActivity> {
            putExtra("jobId", jobId)
        }
    }
}
