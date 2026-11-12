package dev.client.plugin.tiktok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Данные TikTok лежат внутри HTML, в теге script с JSON. Проверяем именно
 * извлечение: если TikTok сменит id тега или обернёт JSON иначе, сломается
 * этот тест, а не пользователь.
 */
class TikTokHtmlTest {

    private fun page(scriptId: String, body: String, attr: String = "id=\"$scriptId\"") =
        """
        <!doctype html><html><head><title>TikTok</title></head>
        <body><div id="root"></div>
        <script $attr type="application/json">$body</script>
        <script src="/app.js"></script></body></html>
        """.trimIndent()

    @Test
    fun `json достаётся из script по id`() {
        val html = page(EMBED_STATE_ID, """{"source":{"data":{"strategy":{"x":1},"/embed/@tiktok":{"page":1}}}}""")
        val state = assertNotNull(scriptJson(html, EMBED_STATE_ID))
        assertTrue(state.has("source"))
    }

    @Test
    fun `порядок атрибутов в теге не важен`() {
        val html = page(
            EMBED_STATE_ID,
            """{"source":{"data":{}}}""",
            attr = "type=\"application/json\" id=\"$EMBED_STATE_ID\"",
        )
        assertNotNull(scriptJson(html, EMBED_STATE_ID))
    }

    @Test
    fun `нет тега — нет данных, без исключения`() {
        val html = page("другой-тег", """{"a":1}""")
        assertNull(scriptJson(html, EMBED_STATE_ID))
    }

    @Test
    fun `не json внутри тега не роняет разбор`() {
        val html = page(EMBED_STATE_ID, "не json вовсе")
        assertNull(scriptJson(html, EMBED_STATE_ID))
    }

    @Test
    fun `узел данных берётся в обход служебного strategy`() {
        val state = assertNotNull(
            parseJsonObject(
                """
                {
                  "source": {
                    "data": {
                      "strategy": { "page_context": {} },
                      "/embed/tag/fyp": { "videoList": [ { "id": "1" } ] }
                    }
                  }
                }
                """.trimIndent()
            )
        )
        val node = assertNotNull(embedNode(state))
        assertEquals(1, node.optJSONArray("videoList")?.length())
    }

    @Test
    fun `страница-заглушка распознаётся как проверка`() {
        assertTrue(looksLikeChallenge("<html><body>Please wait...</body></html>"))
        assertTrue(looksLikeChallenge("<script>var x = 'wafchallenge';</script>"))
        assertTrue(!looksLikeChallenge("<!doctype html><html>обычная страница</html>"))
    }
}
