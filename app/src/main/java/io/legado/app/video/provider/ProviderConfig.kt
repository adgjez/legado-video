package io.legado.app.video.provider

import io.legado.app.help.config.AppConfig

/**
 * Provider 配置，从 AppConfig 读取。
 */
object ProviderConfig {

    val agnesApiKey: String?
        get() = AppConfig.agnesApiKey

    val agnesBaseUrl: String
        get() = AppConfig.agnesBaseUrl

    val providerId: String
        get() = AppConfig.videoGenProvider

    val imageSize: String
        get() = AppConfig.videoGenImageSize

    val concurrency: Int
        get() = AppConfig.videoGenConcurrency

    val targetDurationSec: Int
        get() = AppConfig.videoGenTargetDuration

    /** 根据 providerId 取对应实现（首发仅 agnes） */
    fun create(): VideoGenProvider {
        return when (providerId) {
            "agnes" -> AgnesProvider(agnesBaseUrl, agnesApiKey)
            else -> AgnesProvider(agnesBaseUrl, agnesApiKey)
        }
    }
}
