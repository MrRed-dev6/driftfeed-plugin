package dev.client.plugin.tiktok

import dev.client.provider.Author
import dev.client.provider.Comment
import dev.client.provider.Page
import dev.client.provider.ProviderContext
import dev.client.provider.ProviderError
import dev.client.provider.VideoItem
import org.json.JSONObject

/**
 * Источник данных TikTok: две дороги, каждая для того, что ей доступно.
 *
 *  - `/embed` самого TikTok — профиль, видео по id, страница хештега (поиск).
 *    Обычный HTML, подпись не нужна, посредники не участвуют.
 *  - шлюз `tikwm.com` ([TikTokGateway]) — лента и комментарии. Их у TikTok нельзя
 *    взять без подписи: веб-API отвечает пустым телом даже с валидной
 *    `X-Gnarly`, а в `/embed` этих разделов нет по устройству страницы.
 *
 * Ничего не кэширует: запрос → разбор → Page. Ошибки переводит в ProviderError,
 * чтобы клиент показал осмысленный текст.
 */
internal class TikTokApi(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    private val gateway: TikTokGateway = TikTokGateway(context, config)

    /**
     * Лента. Шлюз отдаёт случайную порцию, курсора у него нет, поэтому курсор
     * здесь — просто номер страницы: прокрутка не упирается в конец, а каждая
     * следующая порция — новые видео.
     */
    suspend fun feed(cursor: String?): Page<VideoItem> {
        val page = cursor?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val raw = gateway.feed()
        val items = ArrayList<VideoItem>(raw.size)
        var photoPosts = 0
        for (entry in raw) {
            val item = parseGatewayItem(entry) ?: continue
            // Фотопосты приходят без ссылки на поток: проигрывать нечего.
            if (item.playUrl.isBlank()) {
                photoPosts++
                continue
            }
            items.add(item)
        }
        if (photoPosts > 0) context.log(TAG, "пропущено фотопостов без видео: $photoPosts")
        if (items.isEmpty()) {
            context.log(TAG, "лента пришла пустой (${raw.size} записей от шлюза)")
            return Page(items = emptyList(), nextCursor = null, hasMore = false)
        }
        return Page(items = items, nextCursor = (page + 1).toString(), hasMore = true)
    }

    /**
     * Поиск по хештегу: другого открытого поиска у TikTok нет.
     *
     * Полнотекстового поиска без подписи не существует: `/api/search/general/full/`
     * отдаёт пустое тело, а у шлюза поиск закрыт Cloudflare. Зато страница
     * хештега открыта, поэтому запрос превращается в тег, а для фразы из
     * нескольких слов проверяются два варианта (см. [hashtagCandidates]).
     */
    suspend fun search(query: String, cursor: String?): Page<VideoItem> {
        val candidates = hashtagCandidates(query)
        if (candidates.isEmpty()) throw ProviderError.Unknown("Пустой поисковый запрос")
        for (tag in candidates) {
            val items = embedNodeOrNull("/tag/$tag")?.let(::playlistItems).orEmpty()
            if (items.isNotEmpty()) {
                if (tag != candidates.first()) context.log(TAG, "по фразе нашёлся только #$tag")
                return Page(items = items, nextCursor = null, hasMore = false)
            }
        }
        context.log(TAG, "по хештегам $candidates ничего не нашлось")
        return Page(items = emptyList(), nextCursor = null, hasMore = false)
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
        val item = parseEmbedVideo(infos, videoData.optJSONObject("authorInfos"))
            ?: throw ProviderError.Unknown("Не удалось разобрать видео $clean")
        if (item.playUrl.isBlank()) {
            // Фотопост: у него есть только обложки, потока нет вообще.
            throw ProviderError.Unknown("У поста $clean нет видео: это фотопост")
        }
        return item
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

    /** Комментарии видео через шлюз: курсор — смещение, то есть номер показанного. */
    suspend fun comments(videoId: String, cursor: String?): Page<Comment> {
        val clean = videoId.trim()
        if (clean.isEmpty() || !clean.all { it.isDigit() }) {
            throw ProviderError.Unknown("Некорректный id видео: $videoId")
        }
        val offset = cursor?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val data = gateway.comments(clean, offset)

        val array = data.optJSONArray("comments")
            ?: return Page(items = emptyList(), nextCursor = null, hasMore = false)
        val items = ArrayList<Comment>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            parseComment(entry)?.let(items::add)
        }

        // hasMore шлюз присылает не всегда: если его нет, считаем по total.
        val total = data.lng("total", "total_count")
        val hasMore = if (data.has("hasMore") && !data.isNull("hasMore")) {
            data.optBoolean("hasMore")
        } else {
            total > offset + items.size
        }
        val next = (offset + items.size).toString()
        return Page(
            items = items,
            nextCursor = if (hasMore) next else null,
            hasMore = hasMore,
        )
    }

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
        var photoPosts = 0
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val item = parsePlaylistItem(entry, author) ?: continue
            // Фотопосты приходят без ссылки на поток: проигрывать нечего,
            // поэтому в ленту они не попадают.
            if (item.playUrl.isBlank()) {
                photoPosts++
                continue
            }
            result.add(item)
        }
        if (photoPosts > 0) context.log(TAG, "пропущено фотопостов без видео: $photoPosts")
        return result
    }

    private companion object {
        const val TAG = "TikTokApi"
    }
}
