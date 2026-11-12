package dev.client.plugin.tiktok

import dev.client.provider.Author
import dev.client.provider.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Разбор данных TikTok. Специально терпимый: одно и то же поле в разных местах
 * называется по-разному (`nickname` / `nickName`, `playAddr` / `urls`),
 * и TikTok меняет это без предупреждения. Поэтому каждый геттер принимает
 * несколько вариантов имени, а тесты фиксируют обе формы.
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

/** Первая непустая ссылка: строка, массив строк или объект с `urlList`. */
internal fun JSONObject.firstUrl(vararg keys: String): String? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        when (val value = opt(key)) {
            is String -> if (value.isNotBlank()) return value
            is JSONArray -> firstString(value)?.let { return it }
            is JSONObject -> value.firstUrl("urlList", "UrlList", "url_list", "urls")?.let { return it }
        }
    }
    return null
}

private fun firstString(array: JSONArray): String? {
    for (i in 0 until array.length()) {
        val item = array.opt(i)
        if (item is String && item.isNotBlank()) return item
        if (item is JSONObject) {
            item.firstUrl("urlList", "UrlList", "url_list", "playAddr", "url")?.let { return it }
        }
    }
    return null
}

/**
 * Автор из любого места, где TikTok его кладёт: `userInfo` страницы профиля,
 * `authorInfos` страницы видео, `author` страницы обычного вида.
 *
 * `id` — это `uniqueId`, а не числовой id: профиль запрашивается по нику
 * (`/embed/@tiktok`), поэтому только ник позволяет сходить в профиль повторно.
 */
internal fun parseAuthor(json: JSONObject?): Author {
    if (json == null) return UNKNOWN_AUTHOR
    val username = json.str("uniqueId", "unique_id", "uniqueIdModify")
    val avatar = json.firstUrl("avatarThumbUrl", "avatarLarger", "avatarThumb", "avatarMedium", "avatar")
        ?: json.firstUrl("covers", "coverUrl")
    return Author(
        id = username ?: json.str("userId", "uid", "id", "secUid", "sec_uid") ?: "unknown",
        username = username ?: "unknown",
        displayName = json.str("nickname", "nickName", "displayName") ?: username ?: "unknown",
        avatarUrl = avatar,
    )
}

private val UNKNOWN_AUTHOR =
    Author(id = "unknown", username = "unknown", displayName = "unknown", avatarUrl = null)

/**
 * Элемент `videoList` со страницы профиля или хештега.
 *
 * Здесь мало полей: нет лайков и комментариев, только просмотры, — и автора
 * в элементе нет, только `authorUniqueId`. Поэтому автор передаётся снаружи:
 * со страницы профиля он известен точно, на странице хештега собирается из ника.
 */
internal fun parsePlaylistItem(json: JSONObject, author: Author?): VideoItem? {
    val id = json.str("id") ?: return null
    val username = json.str("authorUniqueId", "authorUniqueIdModify")
    val resolved = author ?: if (username != null) {
        Author(id = username, username = username, displayName = username, avatarUrl = null)
    } else {
        UNKNOWN_AUTHOR
    }
    return VideoItem(
        id = id,
        playUrl = json.str("playAddr", "playAddrLowBr", "downloadAddr") ?: "",
        coverUrl = json.str("coverUrl", "originCoverUrl", "dynamicCoverUrl"),
        description = json.str("desc", "text", "description") ?: "",
        author = resolved,
        likeCount = json.lng("diggCount", "digg_count"),
        commentCount = json.lng("commentCount", "comment_count"),
    )
}

/**
 * Видео со страницы `/embed/<id>`: `videoData.itemInfos` + `videoData.authorInfos`.
 * Здесь статистика полная — лайки, комментарии, просмотры, — а ссылка на поток
 * лежит массивом `video.urls`.
 */
internal fun parseEmbedVideo(itemInfos: JSONObject, authorInfos: JSONObject?): VideoItem? {
    val id = itemInfos.str("id") ?: return null
    val playUrl = itemInfos.firstUrl("playAddr", "downloadAddr")
        ?: itemInfos.obj("video")?.firstUrl("urls", "playAddr", "url_list")
        ?: ""
    return VideoItem(
        id = id,
        playUrl = playUrl,
        coverUrl = itemInfos.firstUrl("covers", "coversOrigin", "coverUrl", "cover"),
        description = itemInfos.str("text", "desc", "description") ?: "",
        author = parseAuthor(authorInfos),
        likeCount = itemInfos.lng("diggCount", "digg_count"),
        commentCount = itemInfos.lng("commentCount", "comment_count"),
    )
}

/** Поиск идёт по хештегам, поэтому из запроса делается тег: «cat videos» → «catvideos». */
internal fun hashtagOf(query: String): String =
    query.trim()
        .removePrefix("#")
        .filter { it.isLetterOrDigit() || it == '_' }
        .lowercase()

/** Ник из того, что ввёл пользователь: `@TikTok`, `tiktok`, ссылка на профиль. */
internal fun usernameOf(id: String): String {
    val tail = id.trim()
        .removePrefix("https://www.tiktok.com/")
        .removePrefix("https://tiktok.com/")
        .substringAfterLast('/')
        .removePrefix("@")
    return tail.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.lowercase()
}
