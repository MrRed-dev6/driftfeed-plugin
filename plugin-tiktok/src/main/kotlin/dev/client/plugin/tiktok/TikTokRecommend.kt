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
 * Сессию плагин берёт сам ([session]) и сам ставит заголовок `Cookie`. Три
 * источника по порядку: хранилище плагина (туда же кладёт результат импорт),
 * банка Android ([CookieBridge]), куда пишет `WebView`, и ссылка
 * [TikTokConfig.sessionImportUrl] — ручной перенос, когда входить в приложении
 * негде. Клиент во всём этом не участвует: он заморожен и не меняется.
 *
 * Пока строки с `sessionid` нет ни в одном источнике, [hasSession] отвечает
 * `false`, и [TikTokApi] не отправляет запрос вовсе: анонимные cookie (`ttwid`,
 * `msToken`) TikTok принимает, но лента от этого не появляется — проверено,
 * ответ тот же `{"status_code": 0}`.
 */
internal class TikTokRecommend(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    private val signer = TikTokSigner(context)

    /**
     * Найденная строка cookie и признак, что поиск уже сделан. Живут столько же,
     * сколько объект провайдера, то есть один запуск приложения: см. [session].
     */
    private var sessionCache: String? = null
    private var sessionResolved = false

    /**
     * Одна страница ленты. `null` — не вышло; это нормальный запасной путь.
     *
     * Сессию плагин берёт сам ([session]) и сам ставит заголовок `Cookie` —
     * клиент в формировании запроса не участвует.
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
            if (cookie == null || !isSignedIn(cookie)) {
                context.log(TAG, "официальная лента пуста — сессии аккаунта нет")
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
     * Есть ли сессия вошедшего аккаунта — а не просто cookie.
     *
     * Проверяется `sessionid`: анонимные cookie TikTok выдаёт сам при первом
     * заходе, и запрос с ними всё равно вернёт пустую ленту. Отправлять его,
     * зная ответ, — 3 мс подписи и HTTP-вызов в пустоту на каждом старте.
     */
    suspend fun hasSession(): Boolean = session()?.let { isSignedIn(it) } ?: false

    /**
     * Строка `Cookie` для домена TikTok. Порядок источников:
     *
     * 1. банка процесса ([CookieBridge]) — бесплатно и всегда свежая: если в
     *    приложении где-то открывался `tiktok.com` и пользователь вошёл;
     * 2. ссылка [TikTokConfig.sessionImportUrl], если задана: пока она жива,
     *    строка забирается заново при каждом запуске — так обновляется сессия,
     *    которую пришлось переносить вручную;
     * 3. хранилище плагина — то, что осталось от прошлых импортов.
     *
     * Импорт стоит выше хранилища намеренно: истёкшая сессия в хранилище иначе
     * блокировала бы обновление — в ней есть `sessionid`, а рабочий ли он,
     * по строке не видно.
     *
     * Результат запоминается до конца жизни плагина: строка не меняется, а
     * чтение банки — это обращение к `CookieManager`, иногда с переходом на
     * главный поток. Обратная сторона — вход, сделанный после запуска, увидится
     * только со следующего запуска приложения.
     *
     * Значение никогда не логируется: это чужая сессия. В лог уходит только
     * вывод — откуда строка и есть ли в ней вход.
     */
    private suspend fun session(): String? {
        if (sessionResolved) return sessionCache

        val browser = try {
            CookieBridge.read(config.baseUrl + "/")?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            null
        }
        if (browser != null && isSignedIn(browser)) {
            context.log(TAG, "сессия взята из браузера приложения")
            return remember(browser)
        }

        importSession()?.let { return remember(it) }

        val stored = readStored()
        if (stored != null && isSignedIn(stored)) {
            context.log(TAG, "сессия взята из хранилища")
            return remember(stored)
        }

        // Входа нет вовсе. Анонимные cookie не выбрасываем: с ними запросы к
        // открытым ручкам выглядят обычнее, чем совсем без них.
        val anonymous = stored ?: browser
        if (anonymous == null) {
            context.log(
                TAG,
                if (config.sessionImportUrl.isBlank()) {
                    "сессии аккаунта нет: банка пуста, импорт не настроен"
                } else {
                    "сессии аккаунта нет: банка пуста, импорт не дал строки"
                },
            )
        } else if (stored != null) {
            context.log(TAG, "в хранилище только анонимные cookie — входа нет")
        } else {
            context.log(TAG, "в банке браузера только анонимные cookie — входа нет")
        }
        return remember(anonymous)
    }

    /** Запомнить найденное: см. «до конца жизни плагина» в [session]. */
    private fun remember(cookie: String?): String? {
        sessionCache = cookie
        sessionResolved = true
        return cookie
    }

    private suspend fun readStored(): String? =
        try {
            context.getStorage(config.sessionStorageKey)?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            context.log(TAG, "хранилище сессии недоступно: ${e.javaClass.simpleName}")
            null
        }

    /**
     * Строка cookie по ссылке импорта, или `null` — импорт выключен, не удался
     * или по ссылке не сессия.
     *
     * Запрашивается при каждом запуске, пока ссылка задана: так сессия
     * обновляется без нового релиза плагина. Успешная строка ложится в
     * хранилище и остаётся там, когда ссылку удалят.
     *
     * Причина отказа пишется без адреса и без текста ответа: и то, и другое
     * может быть секретом.
     */
    private suspend fun importSession(): String? {
        val url = config.sessionImportUrl.trim()
        if (url.isEmpty()) return null

        val body = try {
            context.httpGet(url, emptyMap()).toString(Charsets.UTF_8)
        } catch (e: Throwable) {
            context.log(TAG, "импорт сессии: запрос не удался (${e.javaClass.simpleName})")
            return null
        }
        val cookie = body.trim()
        if (!isSignedIn(cookie)) {
            context.log(TAG, "импорт сессии: по ссылке не строка cookie с sessionid")
            return null
        }

        try {
            context.setStorage(config.sessionStorageKey, cookie)
        } catch (e: Throwable) {
            // Не беда: строку всё равно вернём и ей же воспользуемся сейчас.
            context.log(TAG, "импорт сессии: не сохранился (${e.javaClass.simpleName})")
        }
        context.log(TAG, "сессия импортирована по ссылке")
        return cookie
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
 * Есть ли в строке cookie вход в аккаунт: именно `sessionid`, а не
 * `sessionid_ss` и не анонимные `ttwid`/`msToken`.
 *
 * Пробелы вокруг имён TikTok не ставит, но `getCookie` может отдать строку с
 * ними после разделителя, поэтому `trim`. Регистр важен: имена cookie
 * регистрозависимы.
 */
internal fun isSignedIn(cookies: String): Boolean =
    cookies.split(';').any { pair ->
        val eq = pair.indexOf('=')
        eq > 0 &&
            pair.substring(0, eq).trim() == "sessionid" &&
            pair.substring(eq + 1).trim().isNotEmpty()
    }

/**
 * Заголовки запроса к официальной ленте.
 *
 * `Cookie` ставит сам плагин — из банки процесса или своего хранилища, а не
 * клиент: см. [TikTokRecommend.session]. `X-Gnarly` добавляется, только если
 * подпись получилась, — сегодня эндпоинт отвечает и без неё.
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
