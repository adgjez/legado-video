package io.legado.app.video.pipeline

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 分镜表。LLM 产出，ShotExecutor 消费。
 *
 * 每镜头两步生成：图 → 图生视频。
 * imagePath/videoPath/status/errorMsg 在执行中写回，用于断点续传。
 */
data class Storyboard(
    var title: String = "",
    var shots: List<Shot> = emptyList()
) {

    companion object {
        fun fromJson(json: String): Result<Storyboard> {
            return GSON.fromJsonObject<Storyboard>(json)
        }
    }

    fun toJson(): String = GSON.toJson(this)

    /** 已完成的镜头数（有 videoPath） */
    val doneCount: Int get() = shots.count { !it.videoPath.isNullOrBlank() }

    /** 是否所有镜头都已产出视频 */
    val allDone: Boolean get() = shots.isNotEmpty() && shots.all { !it.videoPath.isNullOrBlank() }
}

data class Shot(
    var index: Int = 0,
    var imagePrompt: String = "",
    var motionHint: String = "",
    var durationMs: Int = 5000,
    var subtitle: String = "",
    /** 关键帧图片远端 URL（生成后写入） */
    var imageUrl: String? = null,
    /** 关键帧图片本地路径（下载后写入） */
    var imagePath: String? = null,
    /** 镜头视频远端 URL（生成后写入） */
    var videoUrl: String? = null,
    /** 镜头视频本地路径（下载后写入，合成用） */
    var videoPath: String? = null,
    /** 0 pending / 1 ok / 2 failed / 3 skipped */
    var status: Int = 0,
    var errorMsg: String? = null
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_OK = 1
        const val STATUS_FAILED = 2
        const val STATUS_SKIPPED = 3
    }
}
