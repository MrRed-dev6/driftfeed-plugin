package dev.client.plugin.tiktok

import dev.client.provider.Author
import dev.client.provider.Comment
import dev.client.provider.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Разбор ответов TikTok. Специально терпимый: веб-ответы и app-ответы
 * называют одни и те же поля по-разному (`itemList` / `aweme_list`,
 * `diggCount` / `digg_count`), и TikTok меняет это без предупреждения.
 * Поэтому каждый геттер принимает несколько вариантов имени.
 */

internal fun JSONObject.str(vararg keys: String): String? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = opt(key)
        if (value is String && value.isNotBlank()) return value
        if (value is Number) return value.toString()
    }
    return null
}

internal fun JSONObject.lng(vararg keys: String): Long {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        when (val value = opt(key)) {
            is Number -> return value.toLong()
            is String -> value.toLongOrNull()?.let { return it }
        }
    }
    return 0L
}

internal fun JSONObject.obj(vararg keys: String): JSONObject? {
    for (key in keys) {
        val value = optJSONObject(key)
        if (value != null) return value
    }
    return null
}

internal fun JSONObject.arr(vararg keys: String): JSONArray? {
    for (key in keys) {
        val value = optJSONArray(key)
        if (value != null) return value
    }
    return null
}

/** Первая непустая строка из массива ссылок: `url_list`, `UrlList` и т.п. */
internal fun JSONObject.firstUrl(vararg keys: String): String? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        when (val value = opt(key)) {
            is String -> if (value.isNotBlank()) return value
            is JSONArray -> for (i in 0 until value.length()) {
                val item = value.opt(i)
                if (item is String && item.isNotBlank()) return item
            }
            is JSONObject -> value.firstUrl("urlList", "UrlList", "url_list", "uri")?.let { return it }
        }
    }
    return null
}

internal fun parseAuthor(json: JSONObject?): Author {
    if (json == null) return UNKNOWN_AUTHOR
    val avatar = json.obj("avatar_thumb", "avatarThumb", "avatar_medium")?.firstUrl("url_list", "urlList")
        ?: json.firstUrl("avatarThumb", "avatarLarger", "avatar")
    return Author(
        id = json.str("uid", "id", "secUid", "sec_uid") ?: "unknown",
        username = json.str("uniqueId", "unique_id", "uniqueIdModify") ?: "unknown",
        displayName = json.str("nickname", "displayName", "nickName") ?: "unknown",
        avatarUrl = avatar,
    )
}

private val UNKNOWN_AUTHOR = Author(id = "unknown", username = "unknown", displayName = "unknown", avatarUrl = null)

/** Веб-формат: `itemList` → элемент уже нужного вида. App-формат: `aweme_list` → то же самое. */
internal fun parseVideoItem(json: JSONObject): VideoItem? {
    val id = json.str("id", "aweme_id", "awemeId") ?: return null
    val video = json.obj("video") ?: JSONObject()
    val stats = json.obj("stats", "statistics") ?: JSONObject()

    val playUrl = video.firstUrl("playAddr", "play_addr", "downloadAddr", "download_addr")
        ?: video.arr("bitrateInfo", "bitrate_info")
            ?.let { arr ->
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val addr = entry.obj("PlayAddr", "play_addr")?.firstUrl("UrlList", "url_list")
                    if (!addr.isNullOrBlank()) return@let addr
                }
                null
            }
        ?: ""

    val cover = video.firstUrl("cover", "originCover", "origin_cover", "dynamicCover", "dynamic_cover")
        ?: json.firstUrl("cover")

    return VideoItem(
        id = id,
        playUrl = playUrl,
        coverUrl = cover,
        description = json.str("desc", "description") ?: "",
        author = parseAuthor(json.obj("author", "authorInfo")),
        likeCount = stats.lng("diggCount", "digg_count"),
        commentCount = stats.lng("commentCount", "comment_count"),
    )
}

internal fun parseComment(json: JSONObject): Comment? {
    val id = json.str("cid", "id", "comment_id") ?: return null
    return Comment(
        id = id,
        author = parseAuthor(json.obj("user", "author")),
        text = json.str("text", "content") ?: "",
        likeCount = json.lng("digg_count", "diggCount", "like_count"),
        replyCount = json.lng("reply_comment_total", "replyCommentTotal", "reply_count"),
    )
}

/** Курсор приходит то числом, то строкой, а `hasMore` — то bool, то 0/1. */
internal fun JSONObject.cursorValue(): String? {
    if (!has("cursor") || isNull("cursor")) return null
    return when (val value = opt("cursor")) {
        is Number -> value.toLong().toString()
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }
}

internal fun JSONObject.hasMoreValue(): Boolean {
    for (key in listOf("hasMore", "has_more", "hasMoreUp")) {
        if (!has(key) || isNull(key)) continue
        return when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }
    }
    return false
}
