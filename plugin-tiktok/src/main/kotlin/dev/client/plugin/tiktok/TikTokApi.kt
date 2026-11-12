package dev.client.plugin.tiktok

import dev.client.provider.Author
import dev.client.provider.Comment
import dev.client.provider.Page
import dev.client.provider.ProviderContext
import dev.client.provider.ProviderError
import dev.client.provider.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Тонкий слой над веб-эндпоинтами TikTok.
 * Ничего не кэширует и не решает, что показать: только запрос → разбор → Page.
 * Ошибки переводит в общий ProviderError, чтобы клиент показал осмысленный текст.
 */
internal class TikTokApi(
    private val context: ProviderContext,
    private val config: TikTokConfig,
    private val signer: Signer,
    private val session: TikTokSession,
) {

    suspend fun feed(cursor: String?): Page<VideoItem> {
        val json = getJson(
            "/api/recommend/item_list/",
            linkedMapOf(
                "aid" to "1988",
                "app_language" to config.language,
                "app_name" to "tiktok_web",
                "browser_language" to "en-US",
                "browser_name" to "Mozilla",
                "browser_platform" to "Linux aarch64",
                "channel" to "tiktok_web",
                "cookie_enabled" to "true",
                "count" to PAGE_SIZE.toString(),
                "cursor" to (cursor ?: "0"),
                "device_platform" to "web_pc",
                "focus_state" to "true",
                "from_page" to "fyp",
                "history_len" to "0",
                "is_fullscreen" to "false",
                "is_page_visible" to "true",
                "language" to config.language,
                "priority_region" to config.region,
                "region" to config.region,
                "screen_height" to "2400",
                "screen_width" to "1080",
                "tz_name" to "UTC",
            ),
        )
        return toVideoPage(json)
    }

    suspend fun search(query: String, cursor: String?): Page<VideoItem> {
        val json = getJson(
            "/api/search/general/full/",
            linkedMapOf(
                "aid" to "1988",
                "app_language" to config.language,
                "app_name" to "tiktok_web",
                "browser_language" to "en-US",
                "browser_name" to "Mozilla",
                "browser_platform" to "Linux aarch64",
                "channel" to "tiktok_web",
                "cookie_enabled" to "true",
                "count" to PAGE_SIZE.toString(),
                "cursor" to (cursor ?: "0"),
                "device_platform" to "web_pc",
                "from_page" to "search",
                "is_page_visible" to "true",
                "keyword" to query,
                "language" to config.language,
                "priority_region" to config.region,
                "region" to config.region,
                "search_source" to "normal_search",
                "tz_name" to "UTC",
            ),
        )
        return toVideoPage(json)
    }

    suspend fun video(id: String): VideoItem {
        val json = getJson(
            "/api/item/detail/",
            linkedMapOf(
                "aid" to "1988",
                "app_language" to config.language,
                "app_name" to "tiktok_web",
                "browser_language" to "en-US",
                "browser_name" to "Mozilla",
                "channel" to "tiktok_web",
                "cookie_enabled" to "true",
                "device_platform" to "web_pc",
                "itemId" to id,
                "language" to config.language,
                "priority_region" to config.region,
                "region" to config.region,
            ),
        )
        val item = json.obj("itemInfo", "item_info")
            ?.obj("itemStruct", "item_struct")
            ?: throw ProviderError.Unknown("Видео $id не найдено")
        return parseVideoItem(item) ?: throw ProviderError.Unknown("Не удалось разобрать видео $id")
    }

    suspend fun comments(videoId: String, cursor: String?): Page<Comment> {
        val json = getJson(
            "/api/comment/list/",
            linkedMapOf(
                "aid" to "1988",
                "app_language" to config.language,
                "app_name" to "tiktok_web",
                "aweme_id" to videoId,
                "browser_language" to "en-US",
                "browser_name" to "Mozilla",
                "channel" to "tiktok_web",
                "cookie_enabled" to "true",
                "count" to PAGE_SIZE.toString(),
                "cursor" to (cursor ?: "0"),
                "device_platform" to "web_pc",
                "language" to config.language,
                "priority_region" to config.region,
                "region" to config.region,
            ),
        )
        val items = ArrayList<Comment>()
        json.arr("comments", "comment_list")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { parseComment(it)?.let(items::add) }
            }
        }
        return Page(items = items, nextCursor = json.cursorValue(), hasMore = json.hasMoreValue())
    }

    suspend fun profile(id: String): Author {
        val json = getJson(
            "/api/user/detail/",
            linkedMapOf(
                "aid" to "1988",
                "app_language" to config.language,
                "app_name" to "tiktok_web",
                "browser_language" to "en-US",
                "browser_name" to "Mozilla",
                "channel" to "tiktok_web",
                "cookie_enabled" to "true",
                "device_platform" to "web_pc",
                "language" to config.language,
                "priority_region" to config.region,
                "region" to config.region,
                "uniqueId" to id,
            ),
        )
        val user = json.obj("userInfo", "user_info")?.obj("user", "userStruct")
            ?: json.obj("user")
            ?: throw ProviderError.Unknown("Профиль $id не найден")
        return parseAuthor(user)
    }

    // ---------------------------------------------------------------- запрос

    private suspend fun getJson(path: String, params: Map<String, String>): JSONObject {
        // Сессия поднимается из хранилища при первом запросе, а не в конструкторе:
        // конструктор провайдера не suspend, а читать файл синхронно нельзя.
        session.restore()

        // msToken идёт и параметром, и кукой — так же, как его отправляет веб-клиент.
        val allParams = params + ("msToken" to session.msToken)
        val url = config.baseUrl + path + "?" + encode(allParams)
        val signedUrl = signer.sign(url, config.userAgent, session.msToken)

        val headers = linkedMapOf(
            "User-Agent" to config.userAgent,
            "Cookie" to session.cookieHeader(),
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "${config.language}-${config.region},${config.language};q=0.9",
            "Referer" to config.baseUrl + "/",
        )
        headers.putAll(config.extraHeaders)

        val bytes = try {
            context.httpGet(signedUrl, headers)
        } catch (e: ProviderError) {
            throw e
        } catch (e: Throwable) {
            context.log(TAG, "httpGet $path failed: ${e.message}")
            throw ProviderError.Unavailable
        }

        val text = bytes.toString(Charsets.UTF_8)
        return parseBody(path, text)
    }

    private fun parseBody(path: String, text: String): JSONObject {
        if (text.isBlank()) {
            throw ProviderError.Unavailable
        }
        // WAF-челлендж вместо данных: клиент не смог пройти JS-проверку.
        if (text.contains("wafchallenge", ignoreCase = true) ||
            text.contains("Please wait", ignoreCase = true) ||
            text.contains("<html", ignoreCase = true)
        ) {
            throw ProviderError.NeedsLogin
        }

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            context.log(TAG, "bad json from $path: ${text.take(120)}")
            throw ProviderError.Unknown("Некорректный ответ TikTok")
        }

        val status = when {
            json.has("statusCode") -> json.optInt("statusCode", 0)
            json.has("status_code") -> json.optInt("status_code", 0)
            else -> 0
        }
        when (status) {
            0 -> Unit
            2146 -> throw ProviderError.NeedsLogin
            10201, 10202, 10203 -> throw ProviderError.RateLimited
            else -> {
                val msg = json.str("statusMsg", "status_msg") ?: "status $status"
                context.log(TAG, "$path -> $msg")
                throw ProviderError.Unknown(msg)
            }
        }
        return json
    }

    private fun encode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    // ---------------------------------------------------------------- разбор

    private fun toVideoPage(json: JSONObject): Page<VideoItem> {
        val items = extractVideos(json)
        return Page(items = items, nextCursor = json.cursorValue(), hasMore = json.hasMoreValue())
    }

    private fun extractVideos(json: JSONObject): List<VideoItem> {
        for (name in listOf("itemList", "item_list", "aweme_list", "awemeList", "items")) {
            json.arr(name)?.let { return mapVideos(it) }
        }
        // Поиск отдаёт data: [{ type: 1, item: {...} }, ...]
        json.arr("data")?.let { array ->
            val result = ArrayList<VideoItem>()
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val item = entry.obj("item", "aweme_info", "awemeInfo") ?: entry
                parseVideoItem(item)?.let(result::add)
            }
            return result
        }
        return emptyList()
    }

    private fun mapVideos(array: JSONArray): List<VideoItem> {
        val result = ArrayList<VideoItem>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { parseVideoItem(it)?.let(result::add) }
        }
        return result
    }

    private companion object {
        const val TAG = "TikTokApi"
        const val PAGE_SIZE = 10
    }
}
