package dev.client.plugin.tiktok

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор проверяется на двух форматах сразу: веб (`itemList`, `diggCount`)
 * и app (`aweme_list`, `digg_count`). Если TikTok переименует поля,
 * сломается именно этот тест, а не пользователь в ленте.
 */
class TikTokParserTest {

    @Test
    fun `веб-формат видео разбирается`() {
        val json = JSONObject(
            """
            {
              "id": "7300000000000000001",
              "desc": "котик",
              "author": {
                "id": "u1",
                "uniqueId": "catlover",
                "nickname": "Cat Lover",
                "avatarThumb": "https://cdn/a.jpg"
              },
              "video": {
                "playAddr": "https://cdn/v.mp4",
                "cover": "https://cdn/c.jpg"
              },
              "stats": { "diggCount": 1200, "commentCount": 34 }
            }
            """.trimIndent()
        )

        val item = assertNotNull(parseVideoItem(json))
        assertEquals("7300000000000000001", item.id)
        assertEquals("котик", item.description)
        assertEquals("https://cdn/v.mp4", item.playUrl)
        assertEquals("https://cdn/c.jpg", item.coverUrl)
        assertEquals("catlover", item.author.username)
        assertEquals(1200L, item.likeCount)
        assertEquals(34L, item.commentCount)
    }

    @Test
    fun `app-формат видео разбирается`() {
        val json = JSONObject(
            """
            {
              "aweme_id": "7300000000000000002",
              "desc": "пёс",
              "author": {
                "uid": "u2",
                "unique_id": "doglover",
                "nickname": "Dog Lover",
                "avatar_thumb": { "url_list": ["https://cdn/d.jpg"] }
              },
              "video": {
                "play_addr": { "url_list": ["https://cdn/d.mp4"] },
                "cover": { "url_list": ["https://cdn/dc.jpg"] }
              },
              "statistics": { "digg_count": 5, "comment_count": 6 }
            }
            """.trimIndent()
        )

        val item = assertNotNull(parseVideoItem(json))
        assertEquals("7300000000000000002", item.id)
        assertEquals("https://cdn/d.mp4", item.playUrl)
        assertEquals("https://cdn/d.jpg", item.author.avatarUrl)
        assertEquals(5L, item.likeCount)
        assertEquals(6L, item.commentCount)
    }

    @Test
    fun `видео без id отбрасывается`() {
        assertNull(parseVideoItem(JSONObject("""{"desc":"нет id"}""")))
    }

    @Test
    fun `комментарий разбирается`() {
        val json = JSONObject(
            """
            {
              "cid": "c1",
              "text": "первый",
              "user": { "uniqueId": "someone", "nickname": "Someone" },
              "digg_count": 7,
              "reply_comment_total": 2
            }
            """.trimIndent()
        )
        val comment = assertNotNull(parseComment(json))
        assertEquals("c1", comment.id)
        assertEquals("первый", comment.text)
        assertEquals("someone", comment.author.username)
        assertEquals(7L, comment.likeCount)
        assertEquals(2L, comment.replyCount)
    }

    @Test
    fun `курсор понимает и число, и строку`() {
        assertEquals("20", JSONObject("""{"cursor":20}""").cursorValue())
        assertEquals("20", JSONObject("""{"cursor":"20"}""").cursorValue())
        assertNull(JSONObject("""{"cursor":null}""").cursorValue())
        assertNull(JSONObject("""{}""").cursorValue())
    }

    @Test
    fun `hasMore понимает bool и 0-1`() {
        assertTrue(JSONObject("""{"hasMore":true}""").hasMoreValue())
        assertFalse(JSONObject("""{"hasMore":false}""").hasMoreValue())
        assertTrue(JSONObject("""{"has_more":1}""").hasMoreValue())
        assertFalse(JSONObject("""{"has_more":0}""").hasMoreValue())
        assertFalse(JSONObject("""{}""").hasMoreValue())
    }
}
