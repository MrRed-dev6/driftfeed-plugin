package dev.client.plugin.tiktok

import org.json.JSONObject

/**
 * TikTok кладёт данные страницы не в отдельный API-ответ, а в HTML:
 * в тег `<script id="...">` с JSON внутри. Разбор — это найти тег по id
 * и разобрать его содержимое.
 *
 * Так сделаны и обычные страницы (`__UNIVERSAL_DATA_FOR_REHYDRATION__`),
 * и страницы раздела `/embed` (`__FRONTITY_CONNECT_STATE__`). Плагину нужен
 * второй: он отдаётся без подписи.
 */

internal const val EMBED_STATE_ID = "__FRONTITY_CONNECT_STATE__"
internal const val PAGE_STATE_ID = "__UNIVERSAL_DATA_FOR_REHYDRATION__"

/** JSON из `<script id="...">`. null — если тега нет или внутри не JSON. */
internal fun scriptJson(html: String, id: String): JSONObject? {
    val marker = "id=\"$id\""
    val at = html.indexOf(marker)
    if (at < 0) return null
    val open = html.indexOf('>', at)
    if (open < 0) return null
    val close = html.indexOf("</script>", open)
    if (close < 0) return null
    return parseJsonObject(html.substring(open + 1, close))
}

internal fun parseJsonObject(text: String): JSONObject? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed[0] != '{') return null
    return try {
        JSONObject(trimmed)
    } catch (e: Exception) {
        null
    }
}

/**
 * Узел с данными страницы: `source.data[<маршрут>]`.
 *
 * Имя ключа — это сам маршрут (`/embed/@tiktok`, `/embed/tag/fyp`), поэтому
 * опираться на него нельзя: берём первый объект, кроме служебного `strategy`.
 */
internal fun embedNode(state: JSONObject): JSONObject? {
    val data = state.optJSONObject("source")?.optJSONObject("data") ?: return null
    val keys = data.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key == "strategy") continue
        data.optJSONObject(key)?.let { return it }
    }
    return null
}

/** Страница-заглушка вместо данных: WAF просит выполнить JS-проверку. */
internal fun looksLikeChallenge(html: String): Boolean =
    html.contains("wafchallenge", ignoreCase = true) ||
        html.contains("Please wait", ignoreCase = true)
