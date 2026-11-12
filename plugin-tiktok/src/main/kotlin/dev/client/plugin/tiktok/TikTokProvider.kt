package dev.client.plugin.tiktok

import dev.client.provider.Author
import dev.client.provider.Capabilities
import dev.client.provider.Comment
import dev.client.provider.Page
import dev.client.provider.Provider
import dev.client.provider.ProviderContext
import dev.client.provider.VideoItem

/**
 * Источник данных TikTok для клиента driftfeed.
 *
 * Данные берутся со страниц раздела `/embed`, которые отдаются без подписи
 * (см. [TikTokApi]). Возможности объявлены по факту, а не «на всякий случай»:
 * клиент скрывает в интерфейсе то, чего провайдер не умеет, и обещать лишнее
 * значит показать пользователю пустой экран вместо кнопки.
 *
 * Настроек у провайдера нет намеренно: клиент — чужая сторона, и договариваться
 * с ним о новом методе конфигурации значит менять общий контракт. Регион и
 * язык живут в [TikTokConfig] и меняются выпуском новой версии плагина.
 */
class TikTokProvider(private val context: ProviderContext) : Provider {

    private val config: TikTokConfig = TikTokConfig()
    private val api: TikTokApi = TikTokApi(context, config)

    override val id: String = "tiktok"
    override val displayName: String = "TikTok"

    override val capabilities: Capabilities = Capabilities(
        feed = true,
        // Поиск идёт по хештегам: открытого поиска по словам без подписи нет.
        search = true,
        // В /embed комментариев не бывает — не показываем кнопку, которой некуда вести.
        comments = false,
        videoByUrl = true,
        profile = true,
    )

    override suspend fun getFeed(cursor: String?): Page<VideoItem> = api.feed(cursor)

    override suspend fun search(query: String, cursor: String?): Page<VideoItem> =
        api.search(query, cursor)

    override suspend fun getVideo(id: String): VideoItem = api.video(id)

    override suspend fun getComments(videoId: String, cursor: String?): Page<Comment> =
        api.comments()

    override suspend fun getProfile(id: String): Author = api.profile(id)
}
