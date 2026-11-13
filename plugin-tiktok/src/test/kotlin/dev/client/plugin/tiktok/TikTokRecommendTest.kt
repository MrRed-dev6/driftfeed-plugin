package dev.client.plugin.tiktok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Строка запроса к официальной ленте. Проверяется то, из-за чего подпись
 * ломается молча: порядок параметров, кодировка значений и курсор. TikTok
 * считает `X-Gnarly` по этой самой строке, поэтому любое расхождение — это
 * пустой ответ без объяснений, а не ошибка.
 */
class TikTokRecommendTest {

    @Test
    fun `строка запроса содержит курсор и размер страницы`() {
        val query = recommendQuery(cursor = 20, count = 20, region = "US", language = "en")

        assertTrue("cursor=20" in query, query)
        assertTrue("count=20" in query, query)
        assertTrue("aid=1988" in query, query)
        assertTrue("from_page=fyp" in query, query)
    }

    @Test
    fun `пустые значения остаются пустыми, а не теряются`() {
        val query = recommendQuery(cursor = 0, count = 20, region = "US", language = "en")

        // TikTok ждёт оба параметра: пропуск меняет набор полей и подпись.
        assertTrue("priority_region=&" in query, query)
        assertTrue(query.endsWith("referer=&region=US&screen_height=1080&screen_width=1920&tz_name=UTC"), query)
    }

    @Test
    fun `значения кодируются как encodeURIComponent`() {
        assertEquals("US", encodeComponent("US"))
        assertEquals("%20", encodeComponent(" "))
        assertEquals("%D0%BA%D0%BE%D1%82", encodeComponent("кот"))
        // Скобки и апостроф браузер не кодирует — иначе подпись не сойдётся.
        assertEquals("a(b)'c", encodeComponent("a(b)'c"))
        assertEquals("%26%3D%2B", encodeComponent("&=+"))
    }

    @Test
    fun `пробелов и плюсов в строке запроса нет`() {
        val query = recommendQuery(cursor = 0, count = 20, region = "US", language = "en")

        assertFalse(" " in query, "пробел сломает подпись: $query")
        assertFalse("+" in query, "плюс вместо %20 сломает подпись: $query")
    }
}
