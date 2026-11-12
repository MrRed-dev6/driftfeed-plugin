package dev.client.plugin.tiktok

import dev.client.provider.Author
import dev.client.provider.Comment
import dev.client.provider.Page
import dev.client.provider.ProviderContext
import dev.client.provider.ProviderError
import dev.client.provider.VideoItem
import org.json.JSONObject

/**
 * Источник данных поверх HTML-страниц раздела `/embed`.
 *
 * Почему так, а не через `/api/…`: веб-API TikTok отвечает пустым телом без
 * подписи `X-Bogus`/`X-Gnarly`, которую считает только браузерный SDK
 * (`webmssdk.js`, 243 КБ обфусцированного JSVMP). В QuickJS он не работает,
 * а `X-Bogus` сам по себе TikTok уже не принимает. Раздел `/embed` — тот же
 * TikTok, но отдаёт данные обычной страницей: подпись не нужна, cookie не нужны.
 *
 * Что из этого следует по возможностям:
 *  - профиль, видео по id, лента и поиск — есть;
 *  - комментариев в `/embed` нет вообще, поэтому `comments = false`.
 *
 * Ничего не кэширует: запрос → разбор → Page. Ошибки переводит в ProviderError,
 * чтобы клиент показал осмысленный текст.
 */
internal class TikTokApi(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    /**
     * Лента — это популярные хештеги по кругу. Курсор хранит номер тега,
     * а не позицию внутри страницы: постраничности в `/embed` нет.
     */
    suspend fun feed(cursor: String?): Page<VideoItem> {
        val index = cursor?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val tag = config.feedTags[index % config.feedTags.size]
        val items = embedNodeOrNull("/tag/$tag")?.let(::playlistItems).orEmpty()
        val next = (index + 1) % config.feedTags.size
        return Page(items = items, nextCursor = next.toString(), hasMore = items.isNotEmpty())
    }

    /** Поиск по хештегу: другого открытого поиска у TikTok нет. */
    suspend fun search(query: String, cursor: String?): Page<VideoItem> {
        val tag = hashtagOf(query)
        if (tag.isEmpty()) throw ProviderError.Unknown("Пустой поисковый запрос")
        val items = embedNodeOrNull("/tag/$tag")?.let(::playlistItems).orEmpty()
        if (items.isEmpty()) context.log(TAG, "по хештегу #$tag ничего не нашлось")
        return Page(items = items, nextCursor = null, hasMore = false)
    }

    suspend fun video(id: String): VideoItem {
        val clean = id.trim()
        if (clean.isEmpty() || !clean.all { it.isDigit() }) {
            throw ProviderError.Unknown("Некорректный id видео: $id")
        }
        val node = embedNodeOrNull("/$clean")
            ?: throw ProviderError.Unknown("Видео $clean не найдено")
        val videoData = node.optJSONObject("videoData")
            ?: throw ProviderError.Unknown("Видео $clean не найдено")
        val infos = videoData.optJSONObject("itemInfos")
            ?: throw ProviderError.Unknown("Видео $clean не найдено")
        return parseEmbedVideo(infos, videoData.optJSONObject("authorInfos"))
            ?: throw ProviderError.Unknown("Не удалось разобрать видео $clean")
    }

    suspend fun profile(id: String): Author {
        val username = usernameOf(id)
        if (username.isEmpty()) throw ProviderError.Unknown("Некорректный профиль: $id")
        val node = embedNodeOrNull("/@$username")
            ?: throw ProviderError.Unknown("Профиль $username не найден")
        val user = node.optJSONObject("userInfo")
            ?: throw ProviderError.Unknown("Профиль $username не найден")
        return parseAuthor(user)
    }

    /** Комментариев в `/embed` нет: метод существует только чтобы контракт был целым. */
    fun comments(): Page<Comment> = Page(items = emptyList(), nextCursor = null, hasMore = false)

    // ---------------------------------------------------------------- запрос

    private suspend fun embedNodeOrNull(path: String): JSONObject? {
        val html = fetchHtml(config.baseUrl + config.embedPath + path)
        val state = scriptJson(html, EMBED_STATE_ID)
        if (state == null) {
            // Заглушка вместо данных: либо WAF-проверка, либо такого адреса нет.
            if (looksLikeChallenge(html)) throw ProviderError.NeedsLogin
            context.log(TAG, "нет $EMBED_STATE_ID на $path (${html.length} символов)")
            return null
        }
        return embedNode(state)
    }

    private suspend fun fetchHtml(url: String): String {
        val headers = linkedMapOf(
            "User-Agent" to config.userAgent,
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "${config.language}-${config.region},${config.language};q=0.9",
            "Referer" to config.baseUrl + "/",
        )
        headers.putAll(config.extraHeaders)

        val bytes = try {
            context.httpGet(url, headers)
        } catch (e: ProviderError) {
            throw e
        } catch (e: Throwable) {
            context.log(TAG, "httpGet $url failed: ${e.message}")
            throw ProviderError.Unavailable
        }
        if (bytes.isEmpty()) throw ProviderError.Unavailable
        return bytes.toString(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- разбор

    private fun playlistItems(node: JSONObject): List<VideoItem> {
        val array = node.optJSONArray("videoList") ?: return emptyList()
        // На странице профиля автор известен точно — он один для всех видео.
        val author = node.optJSONObject("userInfo")?.let(::parseAuthor)
        val result = ArrayList<VideoItem>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parsePlaylistItem(item, author)?.let(result::add)
        }
        return result
    }

    private companion object {
        const val TAG = "TikTokApi"
    }
}
