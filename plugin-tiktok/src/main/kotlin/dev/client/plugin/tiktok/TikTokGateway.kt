package dev.client.plugin.tiktok

import dev.client.provider.ProviderContext
import dev.client.provider.ProviderError
import org.json.JSONObject

/**
 * Клиент публичного шлюза `tikwm.com`.
 *
 * Зачем шлюз: ленту и комментарии сам TikTok отдаёт только браузеру. Веб-API
 * требует подпись `X-Gnarly`, которую считает 243-килобайтный JSVMP-движок
 * `webmssdk.js`; подпись на чистом JS (`abogus`) TikTok принимает, но данные
 * всё равно не возвращает — пустые ответы приходят даже без подписи вообще.
 * В `/embed` ленты и комментариев нет по устройству раздела.
 *
 * Что это значит для пользователя: id видео и запрос ленты уходят на чужой
 * сервер. Поэтому сюда отдано ровно то, что иначе недоступно, — лента и
 * комментарии; профиль, видео и поиск по хештегам идут напрямую в TikTok.
 *
 * Ответ шлюза всегда `{"code":0,"msg":"success","data":…}`, где `data` — либо
 * массив (лента), либо объект (комментарии). Ненулевой `code` — отказ шлюза,
 * его текст уходит в ProviderError.
 */
internal class TikTokGateway(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    /** Свежая порция ленты: шлюз отдаёт случайную выборку, курсора у него нет. */
    suspend fun feed(): List<JSONObject> {
        val root = getJson("/api/feed/list?region=${config.region}&count=${config.feedPageSize}")
        return items(root)
    }

    /**
     * Настоящие результаты поиска TikTok. Путь закрыт Cloudflare для не-браузеров,
     * поэтому вызывающий обязан уметь обойтись без него — см. [TikTokApi.search].
     */
    suspend fun search(query: String, offset: Int): List<JSONObject> {
        val root = getJson(
            "/api/feed/search?keywords=" + java.net.URLEncoder.encode(query, "UTF-8") +
                "&count=${config.feedPageSize}&cursor=$offset"
        )
        return items(root)
    }

    private fun items(root: JSONObject): List<JSONObject> {
        val data = root.optJSONArray("data") ?: return emptyList()
        val result = ArrayList<JSONObject>(data.length())
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.let(result::add)
        }
        return result
    }

    /**
     * Комментарии видео. `offset` — сколько уже показано: шлюз принимает его
     * в параметре `cursor` и возвращает в `data.cursor` следующее значение.
     */
    suspend fun comments(videoId: String, offset: Int): JSONObject =
        getJson("/api/comment/list?url=$videoId&count=${config.commentPageSize}&cursor=$offset")
            .optJSONObject("data")
            ?: throw ProviderError.Unavailable

    private suspend fun getJson(path: String): JSONObject {
        val url = config.gatewayUrl + path
        val headers = linkedMapOf(
            "User-Agent" to config.userAgent,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "${config.language}-${config.region},${config.language};q=0.9",
            "Referer" to config.gatewayUrl + "/",
        )
        headers.putAll(config.extraHeaders)

        val bytes = try {
            context.httpGet(url, headers)
        } catch (e: ProviderError.NeedsLogin) {
            // 403 у шлюза — это Cloudflare, а не требование входа в TikTok.
            // Иначе пользователь увидел бы «войдите в аккаунт» на пустом месте.
            context.log(TAG, "шлюз ответил 403 (Cloudflare?) на $path")
            throw ProviderError.Unavailable
        } catch (e: ProviderError) {
            throw e
        } catch (e: Throwable) {
            context.log(TAG, "httpGet $url failed: ${e.message}")
            throw ProviderError.Unavailable
        }
        if (bytes.isEmpty()) throw ProviderError.Unavailable

        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (e: Throwable) {
            // Вместо JSON пришла HTML-заглушка Cloudflare.
            context.log(TAG, "не JSON от шлюза на $path (${bytes.size} байт)")
            throw ProviderError.Unavailable
        }
        val code = root.optInt("code", -1)
        if (code != 0) {
            val message = root.optString("msg").ifBlank { "code=$code" }
            context.log(TAG, "шлюз отказал на $path: $message")
            throw ProviderError.Unknown("Шлюз TikTok: $message")
        }
        return root
    }

    private companion object {
        const val TAG = "TikTokGateway"
    }
}
