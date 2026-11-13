package dev.client.plugin.tiktok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разбор выдачи поисковика. Ссылки в настоящей выдаче идут через редирект
 * (`/l/?uddg=…`), поэтому проверяются оба вида: редирект и прямая ссылка.
 */
class TikTokSearchTest {

    @Test
    fun `id видео достаются из редиректа поисковика`() {
        val html = """
            <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.tiktok.com%2F%40catlover%2Fvideo%2F7642821964041587986&amp;rut=abc" class="result-link">TikTok</a>
            <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.tiktok.com%2F%40other%2Fvideo%2F7471313349448305950&amp;rut=def" class="result-link">TikTok</a>
        """.trimIndent()

        assertEquals(
            listOf("7642821964041587986", "7471313349448305950"),
            videoIdsFromSearchHtml(html),
        )
    }

    @Test
    fun `прямые ссылки и повторы не ломают выдачу`() {
        val html = """
            <a href="https://www.tiktok.com/@third/video/7253346147761196290">прямая</a>
            <a href="https://www.tiktok.com/@third/video/7253346147761196290">повтор</a>
            <a href="https://example.com/video/7253346147761196299">чужой сайт</a>
        """.trimIndent()

        assertEquals(listOf("7253346147761196290"), videoIdsFromSearchHtml(html))
    }

    @Test
    fun `капча вместо выдачи не даёт ни одного id`() {
        val html = """
            <html><body><p>Unfortunately, bots use DuckDuckGo too.
            Please complete the following challenge to confirm this search was made by a human.</p></body></html>
        """.trimIndent()

        assertTrue(videoIdsFromSearchHtml(html).isEmpty())
    }
}
