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
    fun `cookie и подпись попадают в заголовки, а без них заголовков нет`() {
        val config = TikTokConfig()
        val cookie = "sessionid=abc; ttwid=def"

        val withBoth = recommendHeaders(config, signature = "sig", cookie = cookie)
        assertEquals("sig", withBoth["X-Gnarly"])
        assertEquals(cookie, withBoth["Cookie"])

        val bare = recommendHeaders(config, signature = null, cookie = null)
        assertFalse("X-Gnarly" in bare, "подпись не посчиталась — заголовка быть не должно")
        assertFalse("Cookie" in bare, "сессии нет — заголовка быть не должно")
        // Остальные заголовки от этого не зависят.
        assertEquals(config.userAgent, bare["User-Agent"])
    }

    @Test
    fun `пробелов и плюсов в строке запроса нет`() {
        val query = recommendQuery(cursor = 0, count = 20, region = "US", language = "en")

        assertFalse(" " in query, "пробел сломает подпись: $query")
        assertFalse("+" in query, "плюс вместо %20 сломает подпись: $query")
    }

    @Test
    fun `вход распознаётся по sessionid`() {
        assertTrue(isSignedIn("ttwid=1%7Cabc; sessionid=deadbeef; msToken=xyz"))
        assertTrue(isSignedIn("sessionid=deadbeef"))
        // Пробелы вокруг разделителя getCookie иногда ставит.
        assertTrue(isSignedIn("ttwid=1;  sessionid=deadbeef ;tt_webid_v2=7"))
    }

    @Test
    fun `анонимные cookie за вход не считаются`() {
        assertFalse(isSignedIn("ttwid=1%7Cabc; msToken=xyz; tt_webid_v2=7"))
        assertFalse(isSignedIn(""))
        // Другая cookie с тем же началом имени — не вход.
        assertFalse(isSignedIn("sessionid_ss=deadbeef; sessionid_backup=x"))
        // Имя cookie регистрозависимо.
        assertFalse(isSignedIn("SessionID=deadbeef"))
        // Пустое значение — тоже не вход.
        assertFalse(isSignedIn("sessionid=; ttwid=1"))
    }
}
