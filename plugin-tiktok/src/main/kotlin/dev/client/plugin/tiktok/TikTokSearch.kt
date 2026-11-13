package dev.client.plugin.tiktok

import dev.client.provider.ProviderContext
import dev.client.provider.ProviderError
import java.net.URLDecoder

/**
 * Поиск через индекс DuckDuckGo — последний путь к настоящему полнотекстовому
 * поиску без своего сервера.
 *
 * Почему так: поиск самого TikTok закрыт подписью `X-Gnarly` (на запрос без неё
 * приходит `url doesn't match`, с ней — пустое тело), у шлюза путь `/feed/search`
 * закрыт Cloudflare, а страница `/search` отдаёт HTML без результатов — они
 * подгружаются тем же закрытым API.
 *
 * DuckDuckGo же индексирует страницы видео вместе с описаниями, поэтому запрос
 * `site:tiktok.com/video <слова>` возвращает ссылки на настоящие видео по словам
 * из описания. Дальше каждый id доразбирается обычной страницей `/embed/<id>`,
 * которая открыта.
 *
 * Ограничения честные: выдача — то, что успело попасть в индекс, то есть
 * популярное, а не свежее; результатов немного (единицы); на частые запросы
 * DuckDuckGo отвечает капчей. Поэтому это не единственный путь: если он не
 * сработал, [TikTokApi] переходит к шлюзу и хештегам.
 */
internal class TikTokSearch(
    private val context: ProviderContext,
    private val config: TikTokConfig,
) {

    /** Id видео по запросу. Пустой список — «ничего не нашлось или не пустили». */
    suspend fun videoIds(query: String): List<String> {
        val searchQuery = "site:" + config.searchSite + " " + query
        val locale = config.region.lowercase() + "-" + config.language.lowercase()
        val url = config.searchEngineUrl + "/lite/?q=" + encode(searchQuery) + "&kl=" + locale
        val html = try {
            context.httpGet(url, headers()).toString(Charsets.UTF_8)
        } catch (e: ProviderError) {
            // 403/429 от поисковика — не ошибка пользователя, просто нет этого пути.
            context.log(TAG, "поисковик отказал: ${e.javaClass.simpleName}")
            return emptyList()
        } catch (e: Throwable) {
            context.log(TAG, "поисковик недоступен: ${e.message}")
            return emptyList()
        }

        val ids = videoIdsFromSearchHtml(html)
        if (ids.isEmpty()) context.log(TAG, "поисковик не дал ссылок на видео")
        return ids
    }

    private fun headers(): Map<String, String> = linkedMapOf(
        "User-Agent" to config.userAgent,
        "Accept" to "text/html,application/xhtml+xml",
        "Accept-Language" to "${config.language}-${config.region},${config.language};q=0.9",
        "Referer" to config.searchEngineUrl + "/",
    )

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TAG = "TikTokSearch"
    }
}

/**
 * Id видео из страницы выдачи поисковика.
 *
 * Ссылки идут через редирект поисковика (`/l/?uddg=<адрес в percent-кодировке>`),
 * поэтому сначала раскодируется адрес, и только потом из него берётся id.
 * Прямые ссылки на видео тоже попадаются — они обрабатываются тем же правилом.
 */
internal fun videoIdsFromSearchHtml(html: String): List<String> {
    val ids = LinkedHashSet<String>()
    for (match in UDDG_REDIRECT.findAll(html)) {
        val target = try {
            URLDecoder.decode(match.groupValues[1], "UTF-8")
        } catch (e: Throwable) {
            match.groupValues[1]
        }
        if (!target.contains("tiktok.com/")) continue
        VIDEO_ID.find(target)?.let { ids.add(it.groupValues[1]) }
    }
    // Прямые ссылки на видео тоже попадают в выдачу — без редиректа поисковика.
    for (match in DIRECT_LINK.findAll(html)) {
        ids.add(match.groupValues[1])
    }
    return ids.toList()
}

/** Ссылки поисковика идут через редирект: `/l/?uddg=<адрес>&rut=…`. */
private val UDDG_REDIRECT = Regex("uddg=([^&\"]+)")

/** Числовой id видео — единственное, что нужно от найденной ссылки. */
private val VIDEO_ID = Regex("/video/(\\d{15,25})")

/** Прямая ссылка вида `tiktok.com/@ник/video/<id>`. */
private val DIRECT_LINK = Regex("tiktok\\.com/@[^/\"'?&]+/video/(\\d{15,25})")
