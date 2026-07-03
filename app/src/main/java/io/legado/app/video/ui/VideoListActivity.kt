package io.legado.app.video.ui

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.VideoJob
import io.legado.app.databinding.ActivityVideoListBinding
import io.legado.app.databinding.ItemVideoListBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频生成历史列表。
 */
class VideoListActivity : BaseActivity<ActivityVideoListBinding>() {

    override val binding by viewBinding(ActivityVideoListBinding::inflate)

    private val adapter by lazy { VideoListAdapter(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        adapter.bindToRecyclerView(binding.recyclerView)
        adapter.setOnItemClickListener { _, item ->
            startActivity<VideoGenActivity> { putExtra("jobId", item.id) }
        }
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { appDb.videoJobDao.all }
            if (list.isEmpty()) {
                binding.tvEmpty.visibility = android.view.View.VISIBLE
                binding.recyclerView.visibility = android.view.View.GONE
            } else {
                binding.tvEmpty.visibility = android.view.View.GONE
                binding.recyclerView.visibility = android.view.View.VISIBLE
            }
            adapter.setItems(list)
        }
    }

    private fun deleteJob(item: VideoJob) {
        alert(R.string.delete) {
            setMessage(getString(R.string.sure_del_any, item.bookName + " " + item.chapterRange))
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        item.outputPath?.let { kotlin.runCatching { File(it).delete() } }
                        appDb.videoJobDao.delete(item.id)
                    }
                    loadData()
                }
            }
            noButton()
        }
    }

    inner class VideoListAdapter(context: Context) :
        RecyclerAdapter<VideoJob, ItemVideoListBinding>(context) {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        override fun getViewBinding(parent: ViewGroup): ItemVideoListBinding {
            return ItemVideoListBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemVideoListBinding,
            item: VideoJob,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                tvBookName.text = item.bookName
                tvChapterRange.text = "${item.chapterRange}  ·  ${dateFormat.format(Date(item.createdAt))}"
                tvStatus.text = statusText(item)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemVideoListBinding) {
            binding.tvDelete.setOnClickListener {
                getItem(holder.layoutPosition)?.let { item -> deleteJob(item) }
            }
        }

        private fun statusText(job: VideoJob): String = when (job.status) {
            VideoJob.STATUS_PENDING -> context.getString(R.string.video_gen_status_pending)
            VideoJob.STATUS_STORYBOARD -> context.getString(R.string.video_gen_status_storyboard)
            VideoJob.STATUS_EXECUTE -> context.getString(R.string.video_gen_status_execute)
            VideoJob.STATUS_COMPOSE -> context.getString(R.string.video_gen_status_compose)
            VideoJob.STATUS_DONE -> context.getString(R.string.video_gen_status_done)
            VideoJob.STATUS_FAILED -> context.getString(R.string.video_gen_status_failed)
            VideoJob.STATUS_CANCELED -> context.getString(R.string.video_gen_status_canceled)
            else -> context.getString(R.string.video_gen_running)
        }
    }
}
