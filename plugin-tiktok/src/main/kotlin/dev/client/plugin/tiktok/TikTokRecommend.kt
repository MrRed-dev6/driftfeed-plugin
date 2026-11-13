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
 * Cookie плагину не передаются контрактом, их подставляет клиент в свой
 * `httpGet`. Пока он этого не делает, [page] вернёт `null` и [TikTokApi]
 * останется на шлюзе — то есть плагин работает и без правки клиента, просто
 * без персональных рекомендаций.
 */
internal class TikTokRecommend(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    private val signer = TikTokSigner(context)

    /** Одна страница ленты. `null` — не вышло; это нормальный запасной путь. */
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

        val url = config.baseUrl + config.recommendPath + "?" + query
        val body = try {
            context.httpGet(url, headers(signature)).toString(Charsets.UTF_8)
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
            // Именно этот ответ приходит без cookie: поля нет вовсе.
            context.log(TAG, "официальная лента пуста — похоже, нет cookie аккаунта")
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

    private fun headers(signature: String?): Map<String, String> = linkedMapOf<String, String>(
        "User-Agent" to config.userAgent,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "${config.language}-${config.region},${config.language};q=0.9",
        "Referer" to config.baseUrl + "/foryou",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
    ).apply {
        // Именно так делает браузер. Сегодня эндпоинт отвечает и без заголовка,
        // но у сессии с аккаунтом проверка может включиться — заголовок дешёвый.
        if (signature != null) put("X-Gnarly", signature)
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
