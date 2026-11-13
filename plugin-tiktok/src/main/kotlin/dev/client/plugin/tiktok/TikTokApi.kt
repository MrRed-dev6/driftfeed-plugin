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
 *  - сам TikTok — лента ([TikTokRecommend]), профиль, видео по id, страница
 *    хештега и комментарии (`/api/comment/list/` открыт без подписи, им же
 *    пользуется встроенный плеер на чужих сайтах);
 *  - шлюз `tikwm.com` ([TikTokGateway]) — запасная лента на случай, когда у
 *    официальной нет аккаунта, и запасные комментарии.
 *
 * Ничего не кэширует: запрос → разбор → Page. Ошибки переводит в ProviderError,
 * чтобы клиент показал осмысленный текст.
 */
internal class TikTokApi(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    private val gateway: TikTokGateway = TikTokGateway(context, config)
    private val searchEngine: TikTokSearch = TikTokSearch(context, config)
    private val recommend: TikTokRecommend = TikTokRecommend(context, config)

    /**
     * Откуда брать ленту. Решается один раз за сессию и запоминается: если
     * официальная лента не отдала ни одного элемента (нет сессии аккаунта),
     * незачем подписывать и ждать её на каждой странице.
     */
    private var feedSource = FeedSource.UNKNOWN

    /**
     * Лента.
     *
     * Сначала официальная — `/api/recommend/item_list/`, то есть настоящие
     * рекомендации «Для тебя» с честным курсором. Ей нужна подпись
     * `X-Gnarly` ([TikTokSigner]) и сессия вошедшего аккаунта: подпись плагин
     * считает сам, а сессию читает из своего хранилища ([TikTokRecommend]).
     *
     * Сессию в хранилище кладёт клиент, и сейчас он этого не делает — по
     * своему решению. Поэтому [TikTokRecommend.hasSession] обычно отвечает
     * `false`, и запрос к официальной ленте не отправляется вовсе: подписывать
     * его, зная, что ответ будет `{"status_code": 0}`, — это лишние 3 мс и
     * лишний запрос на каждый старт. Путь остаётся рабочим на случай, если
     * клиент передумает: тогда он появится сам, без правок в плагине.
     *
     * Курсор различает источники: официальный идёт с префиксом `fyp:`, у шлюза
     * это просто номер страницы. Так следующая страница не переедет из одного
     * источника в другой.
     */
    suspend fun feed(cursor: String?): Page<VideoItem> {
        if (feedSource != FeedSource.GATEWAY && recommend.hasSession()) {
            val start = officialCursor(cursor)
            if (start != null) {
                val result = recommend.page(start)
                if (result != null && result.items.isNotEmpty()) {
                    feedSource = FeedSource.OFFICIAL
                    return Page(
                        items = result.items,
                        nextCursor = if (result.hasMore) OFFICIAL_CURSOR + result.cursor else null,
                        hasMore = result.hasMore,
                    )
                }
                if (start == 0) {
                    context.log(TAG, "официальная лента не пришла — дальше беру шлюз")
                    feedSource = FeedSource.GATEWAY
                }
            }
        }
        return gatewayFeed(cursor)
    }

    /** Номер страницы в официальной ленте, или null — курсор не её. */
    private fun officialCursor(cursor: String?): Int? {
        val token = cursor?.trim().orEmpty()
        if (token.isEmpty()) return 0
        if (!token.startsWith(OFFICIAL_CURSOR)) return null
        return token.removePrefix(OFFICIAL_CURSOR).toIntOrNull()?.takeIf { it >= 0 }
    }

    /**
     * Лента шлюза. Он отдаёт случайную порцию популярного по региону, курсора у
     * него нет, поэтому курсор здесь — просто номер страницы: прокрутка не
     * упирается в конец, а каждая следующая порция — новые видео.
     */
    private suspend fun gatewayFeed(cursor: String?): Page<VideoItem> {
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
     * Поиск. Три пути по убыванию качества, каждый следующий — запасной:
     *
     *  1. настоящие результаты TikTok через шлюз (`/api/feed/search`) — свежие,
     *     с полной статистикой, но Cloudflare пускает туда только браузеры;
     *  2. полнотекстовый поиск по индексу поисковика ([TikTokSearch]): слова
     *     ищутся по описаниям проиндексированных видео, глубина — одна страница;
     *  3. хештег из запроса — то, что открыто у самого TikTok.
     *
     * Почему не поиск TikTok напрямую: `/api/search/general/full/` без подписи
     * отвечает `url doesn't match`, с подписью `X-Gnarly` — пустым телом.
     */
    suspend fun search(query: String, cursor: String?): Page<VideoItem> {
        val text = query.trim()
        if (text.isEmpty()) throw ProviderError.Unknown("Пустой поисковый запрос")
        val offset = cursor?.toIntOrNull()?.takeIf { it >= 0 } ?: 0

        gatewaySearch(text, offset)?.let { return it }

        // У индекса нет глубины: вторая страница пошла бы по кругу.
        if (offset == 0) {
            val byIndex = searchByIndex(text)
            if (byIndex.isNotEmpty()) return Page(items = byIndex, nextCursor = null, hasMore = false)
        }

        return searchByHashtags(text)
    }

    private suspend fun gatewaySearch(query: String, offset: Int): Page<VideoItem>? {
        val raw = try {
            gateway.search(query, offset)
        } catch (e: Throwable) {
            context.log(TAG, "поиск через шлюз не вышел: ${e.message}")
            return null
        }
        val items = ArrayList<VideoItem>(raw.size)
        for (entry in raw) {
            val item = parseGatewayItem(entry) ?: continue
            if (item.playUrl.isNotBlank()) items.add(item)
        }
        if (items.isEmpty()) return null
        return Page(items = items, nextCursor = (offset + items.size).toString(), hasMore = true)
    }

    /** Поиск по словам через индекс: id видео → страница `/embed/<id>` → элемент. */
    private suspend fun searchByIndex(query: String): List<VideoItem> {
        val ids = searchEngine.videoIds(query)
        if (ids.isEmpty()) return emptyList()
        val result = ArrayList<VideoItem>(ids.size)
        for (id in ids.take(config.searchDetailLimit)) {
            val item = try {
                embedVideoOrNull(id)
            } catch (e: Throwable) {
                context.log(TAG, "видео $id из индекса не открылось: ${e.message}")
                null
            }
            // Фотопосты в выдачу не берём: играть нечего.
            if (item == null || item.playUrl.isBlank()) continue
            result.add(item)
        }
        return result
    }

    private suspend fun searchByHashtags(query: String): Page<VideoItem> {
        val candidates = hashtagCandidates(query)
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
        val item = embedVideoOrNull(clean)
            ?: throw ProviderError.Unknown("Видео $clean не найдено")
        if (item.playUrl.isBlank()) {
            // Фотопост: у него есть только обложки, потока нет вообще.
            throw ProviderError.Unknown("У поста $clean нет видео: это фотопост")
        }
        return item
    }

    /** Общая часть [video] и поиска по индексу: страница `/embed/<id>` → элемент. */
    private suspend fun embedVideoOrNull(id: String): VideoItem? {
        val node = embedNodeOrNull("/$id") ?: return null
        val videoData = node.optJSONObject("videoData") ?: return null
        val infos = videoData.optJSONObject("itemInfos") ?: return null
        return parseEmbedVideo(infos, videoData.optJSONObject("authorInfos"))
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

    /**
     * Комментарии видео — напрямую у TikTok.
     *
     * `/api/comment/list/` — тот самый эндпоинт, которым пользуется встроенный
     * плеер TikTok на чужих сайтах, поэтому подписи он не требует. Курсор —
     * смещение; TikTok возвращает своё значение в `cursor`, и оно точнее нашего,
     * потому что часть комментариев он отфильтровывает и присылает меньше
     * запрошенного.
     *
     * Если эндпоинт всё же закроют, остаётся шлюз: он отдаёт тот же набор
     * полей, поэтому разбор общий.
     */
    suspend fun comments(videoId: String, cursor: String?): Page<Comment> {
        val clean = videoId.trim()
        if (clean.isEmpty() || !clean.all { it.isDigit() }) {
            throw ProviderError.Unknown("Некорректный id видео: $videoId")
        }
        val offset = cursor?.toIntOrNull()?.takeIf { it >= 0 } ?: 0

        officialComments(clean, offset)?.let { return it }
        context.log(TAG, "комментарии через TikTok не пришли, иду в шлюз")
        return gatewayComments(clean, offset)
    }

    private suspend fun officialComments(videoId: String, offset: Int): Page<Comment>? {
        val url = config.baseUrl + "/api/comment/list/?aweme_id=" + videoId +
            "&aid=1988&count=" + config.commentPageSize + "&cursor=" + offset
        val headers = linkedMapOf(
            "User-Agent" to config.userAgent,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "${config.language}-${config.region},${config.language};q=0.9",
            "Referer" to config.baseUrl + "/",
        )
        val root = try {
            val bytes = context.httpGet(url, headers)
            // Пустое тело — TikTok так «мягко» отказывает; это не ошибка разбора.
            if (bytes.isEmpty()) return null
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (e: Throwable) {
            context.log(TAG, "комментарии TikTok: ${e.message}")
            return null
        }
        if (root.optInt("status_code", -1) != 0) {
            context.log(TAG, "комментарии TikTok: status_msg=${root.optString("status_msg")}")
            return null
        }
        val array = root.optJSONArray("comments")
        if (array == null) return null

        val items = ArrayList<Comment>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            parseOfficialComment(entry)?.let(items::add)
        }
        if (items.isEmpty() && offset == 0) {
            // Видео без комментариев — это нормально, отдаём пустую страницу.
            return Page(items = emptyList(), nextCursor = null, hasMore = false)
        }

        val total = root.lng("total")
        val serverCursor = root.optLong("cursor", 0L).toInt()
        val nextOffset = if (serverCursor > offset) serverCursor else offset + items.size
        val hasMore = root.optInt("has_more", 0) == 1 || total > nextOffset
        return Page(
            items = items,
            nextCursor = if (hasMore) nextOffset.toString() else null,
            hasMore = hasMore,
        )
    }

    /** Запасной путь: тот же список, но через шлюз. */
    private suspend fun gatewayComments(videoId: String, offset: Int): Page<Comment> {
        val data = gateway.comments(videoId, offset)
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

    /** Чью ленту ждать: пока не решили — пробуем официальную. */
    private enum class FeedSource { UNKNOWN, OFFICIAL, GATEWAY }

    private companion object {
        const val TAG = "TikTokApi"

        /**
         * Префикс курсора официальной ленты. Он отделяет её от номеров страниц
         * шлюза, чтобы вторая страница не уехала в другой источник.
         */
        const val OFFICIAL_CURSOR = "fyp:"
    }
}
