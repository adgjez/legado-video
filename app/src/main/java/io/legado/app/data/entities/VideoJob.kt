package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.GSON

/**
 * 视频生成任务
 *
 * status: 0待生成 1剧本 2执行 3合成 4完成 5失败 6取消
 */
@Entity(tableName = "videoJobs")
data class VideoJob(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    val bookUrl: String,
    val bookName: String,
    val chapterRange: String,            // "ch12" / "ch1-ch3" / "selection"
    val provider: String,                // "agnes"
    @ColumnInfo(defaultValue = "0")
    var status: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var progress: Int = 0,
    var storyboardJson: String? = null,  // 分镜表 + 每镜头产物路径（断点续传用）
    var outputPath: String? = null,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    var errorMsg: String? = null
) {

    fun equal(other: VideoJob): Boolean {
        return id == other.id && bookUrl == other.bookUrl && chapterRange == other.chapterRange
    }

    companion object {

        const val STATUS_PENDING = 0
        const val STATUS_STORYBOARD = 1
        const val STATUS_EXECUTE = 2
        const val STATUS_COMPOSE = 3
        const val STATUS_DONE = 4
        const val STATUS_FAILED = 5
        const val STATUS_CANCELED = 6
    }
}
