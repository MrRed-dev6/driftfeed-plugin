package dev.client.plugin.tiktok

import dev.client.provider.ProviderContext
import dev.client.provider.VideoItem
import org.json.JSONObject

/**
 * Официальная лента TikTok — `/api/recommend/item_list/`, то есть настоящий
 * алгоритм «Для тебя».
 *
 * В отличие от ленты шлюза ([TikTokGateway]) здесь есть и персональные
 * рекомендации, и настоящая постраничность (`cursor`, `hasMore`).
 *
 * Главное условие — cookie вошедшего аккаунта: без них TikTok отвечает
 * `{"status_code": 0}`, то есть формально успехом и пустым списком. Проверено,
 * что этот ответ одинаков с подписью `X-Gnarly` и без неё, поэтому подпись
 * ([TikTokSigner]) уходит как страховка, а не как пропуск: её отсутствие
 * запрос не отменяет.
 *
 * Сессию плагин держит сам: читает строку cookie из своего хранилища
 * (`getStorage("cookies:www.tiktok.com")`) и сам ставит заголовок `Cookie`.
 * Значение туда кладёт клиент после входа — но сейчас он этого не делает по
 * своему решению, поэтому [hasSession] отвечает `false` и [TikTokApi] ленту
 * отсюда не спрашивает вовсе. Код остаётся рабочим: появится строка в
 * хранилище — включится без правок.
 */
internal class TikTokRecommend(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    private val signer = TikTokSigner(context)

    /**
     * Одна страница ленты. `null` — не вышло; это нормальный запасной путь.
     *
     * Сессию плагин берёт из своего хранилища сам ([session]) и сам ставит
     * заголовок `Cookie` — клиент в формировании запроса не участвует.
     */
    suspend fun page(cursor: Int): Result? {
        val query = recommendQuery(
            cursor = cursor,
            count = config.feedPageSize,
            region = config.region,
            language = config.language,
        )
        // Подпись — страховка, а не пропуск: без cookie эндпоинт отвечает пусто
        // и с ней, и без неё. Если подписать не вышло, запрос всё равно уходит —
        // терять целый источник из-за необязательного заголовка незачем.
        val signature = signer.sign(query, config.userAgent)
        val cookie = session()

        val url = config.baseUrl + config.recommendPath + "?" + query
        val body = try {
            context.httpGet(url, recommendHeaders(config, signature, cookie)).toString(Charsets.UTF_8)
        } catch (e: Throwable) {
            context.log(TAG, "официальная лента не ответила: ${e.message}")
            return null
        }
        // Пустое тело — так TikTok отказывает, не объясняя причину.
        if (body.isBlank()) {
            context.log(TAG, "официальная лента: пустое тело")
            return null
        }

        val root = try {
            JSONObject(body)
        } catch (e: Throwable) {
            context.log(TAG, "официальная лента: не JSON (${body.length} байт)")
            return null
        }
        if (root.optInt("status_code", -1) != 0) {
            context.log(TAG, "официальная лента: status_msg=${root.optString("status_msg")}")
            return null
        }

        val array = root.optJSONArray("itemList")
        if (array == null) {
            // Именно этот ответ приходит без сессии: поля нет вовсе.
            if (cookie == null) {
                context.log(TAG, "официальная лента пуста — в хранилище нет сессии аккаунта")
            } else {
                context.log(TAG, "официальная лента пуста — сессия есть, но TikTok её не принял")
            }
            return null
        }

        val items = ArrayList<VideoItem>(array.length())
        var photoPosts = 0
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val item = parseFeedItem(entry) ?: continue
            if (item.playUrl.isBlank()) {
                photoPosts++
                continue
            }
            items.add(item)
        }
        if (photoPosts > 0) context.log(TAG, "пропущено фотопостов без видео: $photoPosts")

        val next = root.optLong("cursor", (cursor + items.size).toLong()).toInt()
        val hasMore = root.optBoolean("hasMore", false)
        return Result(items = items, cursor = next, hasMore = hasMore)
    }

    /**
     * Есть ли сессия аккаунта — своя в хранилище или браузерная в банке Android.
     *
     * Без сессии официальная лента не запрашивается вообще: без неё TikTok
     * отвечает `{"status_code": 0}`, и подписывать такой запрос незачем.
     */
    suspend fun hasSession(): Boolean = session() != null

    /**
     * Строка `Cookie` для домена TikTok: сначала из хранилища плагина, потом из
     * системной банки.
     *
     * Две дороги — потому что первая появится, только если клиент передумает
     * и начнёт класть сессию сам ([TikTokConfig.sessionStorageKey]). Вторая
     * работает уже сейчас и от клиента ничего не требует: [CookieBridge] читает
     * ту же банку, куда пишет `WebView`, — общий на процесс публичный API.
     *
     * Значение никогда не логируется: это чужая сессия.
     */
    private suspend fun session(): String? {
        val stored = try {
            context.getStorage(config.sessionStorageKey)?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            context.log(TAG, "хранилище сессии недоступно: ${e.message}")
            null
        }
        if (stored != null) return stored

        val browser = CookieBridge.read(config.baseUrl + "/")
        if (browser != null) context.log(TAG, "сессия взята из браузера приложения")
        return browser
    }

    /** Страница ленты и её место в выдаче. */
    internal data class Result(
        val items: List<VideoItem>,
        val cursor: Int,
        val hasMore: Boolean,
    )

    private companion object {
        const val TAG = "TikTokRecommend"
    }
}

/**
 * Заголовки запроса к официальной ленте.
 *
 * `Cookie` ставит сам плагин — из своего хранилища, а не клиент: см.
 * [TikTokRecommend.session]. `X-Gnarly` добавляется, только если подпись
 * получилась, — сегодня эндпоинт отвечает и без неё.
 */
internal fun recommendHeaders(
    config: TikTokConfig,
    signature: String?,
    cookie: String?,
): Map<String, String> = linkedMapOf<String, String>(
    "User-Agent" to config.userAgent,
    "Accept" to "application/json, text/plain, */*",
    "Accept-Language" to "${config.language}-${config.region},${config.language};q=0.9",
    "Referer" to config.baseUrl + "/foryou",
    "X-Requested-With" to "XMLHttpRequest",
    "Sec-Fetch-Dest" to "empty",
    "Sec-Fetch-Mode" to "cors",
    "Sec-Fetch-Site" to "same-origin",
).apply {
    if (signature != null) put("X-Gnarly", signature)
    if (cookie != null) put("Cookie", cookie)
}

/**
 * Строка запроса для `/api/recommend/item_list/` — ровно в том виде, в каком
 * она уйдёт в URL: подпись считается по ней, поэтому собирать её надо один раз
 * и не пересобирать по дороге.
 *
 * Значения кодируются как `encodeURIComponent` в браузере (`%20` вместо `+`,
 * скобки и апостроф не кодируются): TikTok сверяет подпись с тем, что видит в
 * URL, и `+` вместо `%20` в пробеле делает подпись недействительной. Поэтому
 * значения подобраны так, чтобы пробелов в них вообще не было.
 */
internal fun recommendQuery(cursor: Int, count: Int, region: String, language: String): String {
    val params = listOf(
        "aid" to "1988",
        "app_language" to language,
        "app_name" to "tiktok_web",
        "browser_language" to "$language-$region",
        "browser_name" to "Mozilla",
        "browser_online" to "true",
        "browser_platform" to "Win32",
        "browser_version" to "5.0",
        "channel" to "tiktok_web",
        "cookie_enabled" to "true",
        "count" to count.toString(),
        "cursor" to cursor.toString(),
        "device_platform" to "web_pc",
        "focus_state" to "true",
        "from_page" to "fyp",
        "history_len" to "3",
        "is_fullscreen" to "false",
        "is_page_visible" to "true",
        "language" to language,
        "os" to "windows",
        "priority_region" to "",
        "referer" to "",
        "region" to region,
        "screen_height" to "1080",
        "screen_width" to "1920",
        "tz_name" to "UTC",
    )
    return params.joinToString("&") { (key, value) -> key + "=" + encodeComponent(value) }
}

/** `encodeURIComponent` из браузера: не кодирует `A-Z a-z 0-9 - _ . ! ~ * ' ( )`. */
internal fun encodeComponent(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        val plain = (char in 'A'..'Z') || (char in 'a'..'z') || (char in '0'..'9') ||
            char == '-' || char == '_' || char == '.' || char == '!' ||
            char == '~' || char == '*' || char == '\'' || char == '(' || char == ')'
        if (plain) {
            out.append(char)
        } else {
            out.append('%')
            out.append(HEX[code shr 4])
            out.append(HEX[code and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
