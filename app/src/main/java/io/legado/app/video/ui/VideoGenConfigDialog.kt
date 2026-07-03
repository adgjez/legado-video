package io.legado.app.video.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogVideoGenConfigBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.video.VideoGenStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频生成配置对话框。
 *
 * 入口模式：
 *  - "read"：来自阅读页，默认当前章节，可选选中文本或章节范围。
 *  - "info"：来自书籍详情页，默认章节范围。
 *
 * 参数（arguments）：
 *  - bookUrl / bookName
 *  - entryMode ("read" | "info")
 *  - selectedText (read 模式可选)
 *  - currentChapterIndex / totalChapters
 */
class VideoGenConfigDialog : BaseDialogFragment(R.layout.dialog_video_gen_config) {

    private val binding by viewBinding(DialogVideoGenConfigBinding::bind)

    private val bookUrl by lazy { arguments?.getString("bookUrl").orEmpty() }
    private val bookName by lazy { arguments?.getString("bookName").orEmpty() }
    private val entryMode by lazy { arguments?.getString("entryMode") ?: "read" }
    private val selectedText by lazy { arguments?.getString("selectedText") }
    private val currentChapterIndex by lazy { arguments?.getInt("currentChapterIndex", 0) ?: 0 }
    private val totalChapters by lazy { arguments?.getInt("totalChapters", 0) ?: 0 }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(bottomBackground)
        binding.tvTitle.text = getString(R.string.video_gen_config)
        binding.tvBookName.text = bookName

        val hasSelection = !selectedText.isNullOrBlank()
        binding.rbSelection.isEnabled = hasSelection
        if (!hasSelection) binding.rbSelection.alpha = 0.4f

        when (entryMode) {
            "info" -> {
                binding.rbCurrent.isEnabled = false
                binding.rbCurrent.alpha = 0.4f
                binding.rbChapters.isChecked = true
                showChapterRange(true)
            }
            else -> {
                binding.rbCurrent.isChecked = true
                if (hasSelection) {
                    // 默认优先使用选中文本
                }
            }
        }

        val from = binding.npFrom
        val to = binding.npTo
        val maxIdx = (totalChapters - 1).coerceAtLeast(0)
        from.minValue = 0
        from.maxValue = maxIdx
        to.minValue = 0
        to.maxValue = maxIdx
        from.value = currentChapterIndex.coerceIn(0, maxIdx)
        to.value = currentChapterIndex.coerceIn(0, maxIdx)
        from.setOnValueChangedListener { _, _, newVal ->
            if (newVal > to.value) to.value = newVal
        }
        to.setOnValueChangedListener { _, _, newVal ->
            if (newVal < from.value) from.value = newVal
        }

        binding.rgRange.setOnCheckedChangeListener { _, checkedId ->
            showChapterRange(checkedId == R.id.rb_chapters)
        }

        val duration = binding.npDuration
        duration.minValue = 5
        duration.maxValue = 300
        duration.value = AppConfig.videoGenTargetDuration.coerceIn(5, 300)

        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvStart.setOnClickListener { onStartClicked(duration.value, from.value, to.value) }
    }

    private fun showChapterRange(show: Boolean) {
        binding.llChapterRange.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun onStartClicked(durationSec: Int, fromIdx: Int, toIdx: Int) {
        val checkedId = binding.rgRange.checkedRadioButtonId
        when (checkedId) {
            R.id.rb_selection -> {
                val text = selectedText.orEmpty()
                if (text.isBlank()) {
                    toast(R.string.video_gen_no_content)
                    return
                }
                launchGen(text, "selection", durationSec)
            }
            R.id.rb_chapters -> {
                gatherRange(fromIdx, toIdx, durationSec)
            }
            else -> {
                // 当前章节：优先取 ReadBook 内存中的正文
                val inMem = ReadBook.curTextChapter
                if (inMem != null && entryMode == "read") {
                    val text = inMem.getContent()
                    if (text.isBlank()) {
                        toast(R.string.video_gen_no_content)
                        return
                    }
                    launchGen(text, "ch${inMem.chapter.index}", durationSec)
                } else {
                    gatherRange(currentChapterIndex, currentChapterIndex, durationSec)
                }
            }
        }
    }

    private fun gatherRange(fromIdx: Int, toIdx: Int, durationSec: Int) {
        if (AppConfig.agnesApiKey.isNullOrBlank()) {
            toast(R.string.video_gen_no_api_key)
            return
        }
        val (lo, hi) = if (fromIdx <= toIdx) fromIdx to toIdx else toIdx to fromIdx
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { loadRangeContent(lo, hi) }
            if (content.isNullOrBlank()) {
                toast(R.string.video_gen_no_content)
                return@launch
            }
            launchGen(content, "ch${lo}-ch${hi}", durationSec)
        }
    }

    private fun loadRangeContent(fromIdx: Int, toIdx: Int): String? {
        val book = appDb.bookDao.getBook(bookUrl) ?: return null
        val chapters = appDb.bookChapterDao.getChapterList(bookUrl, fromIdx, toIdx)
        if (chapters.isEmpty()) return null
        val sb = StringBuilder()
        for (chapter in chapters) {
            val text = BookHelp.getContent(book, chapter)
            if (!text.isNullOrBlank()) {
                sb.append(chapter.title).append("\n")
                sb.append(text).append("\n\n")
            }
        }
        return sb.toString().ifBlank { null }
    }

    private fun launchGen(content: String, chapterRange: String, durationSec: Int) {
        AppConfig.videoGenTargetDuration = durationSec
        VideoGenStarter.start(
            context = requireContext(),
            bookUrl = bookUrl,
            bookName = bookName,
            chapterRange = chapterRange,
            content = content
        )
        dismiss()
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun args(
            book: Book,
            entryMode: String,
            selectedText: String? = null,
            currentChapterIndex: Int = 0,
            totalChapters: Int = 0
        ): VideoGenConfigDialog = VideoGenConfigDialog().apply {
            arguments = Bundle().apply {
                putString("bookUrl", book.bookUrl)
                putString("bookName", book.name)
                putString("entryMode", entryMode)
                putString("selectedText", selectedText)
                putInt("currentChapterIndex", currentChapterIndex)
                putInt("totalChapters", totalChapters)
            }
        }
    }
}
