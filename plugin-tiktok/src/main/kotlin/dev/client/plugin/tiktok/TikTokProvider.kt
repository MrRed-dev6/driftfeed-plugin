package dev.client.plugin.tiktok

import dev.client.provider.Author
import dev.client.provider.Capabilities
import dev.client.provider.Comment
import dev.client.provider.Page
import dev.client.provider.Provider
import dev.client.provider.ProviderContext
import dev.client.provider.VideoItem

/**
 * Настоящий источник данных.
 *
 * Подпись запросов считает JS-движок клиента (см. [Signer]).
 * Если SDK подписи в плагине нет, провайдер честно объявляет нулевые
 * возможности — вместо того чтобы падать на первом запросе.
 *
 * Настроек у провайдера нет намеренно: клиент — чужая сторона, и договариваться
 * с ним о новом методе конфигурации значит менять общий контракт. Регион и
 * язык живут в [TikTokConfig] и меняются выпуском новой версии плагина.
 */
class TikTokProvider(private val context: ProviderContext) : Provider {

    private val config: TikTokConfig = TikTokConfig()
    private val storage: Storage = ProviderStorage(context)
    private val session: TikTokSession = TikTokSession(storage)
    private val signer: Signer = JsSigner(context)
    private val api: TikTokApi = TikTokApi(context, config, signer, session)

    override val id: String = "tiktok"
    override val displayName: String = "TikTok"

    override val capabilities: Capabilities
        get() = if (signer.available) {
            Capabilities(feed = true, search = true, comments = true, videoByUrl = true, profile = true)
        } else {
            // Без подписи TikTok не отдаёт ни ленту, ни поиск, ни комментарии.
            Capabilities(feed = false, search = false, comments = false, videoByUrl = false, profile = false)
        }

    override suspend fun getFeed(cursor: String?): Page<VideoItem> = api.feed(cursor)

    override suspend fun search(query: String, cursor: String?): Page<VideoItem> =
        api.search(query, cursor)

    override suspend fun getVideo(id: String): VideoItem = api.video(id)

    override suspend fun getComments(videoId: String, cursor: String?): Page<Comment> =
        api.comments(videoId, cursor)

    override suspend fun getProfile(id: String): Author = api.profile(id)
}
