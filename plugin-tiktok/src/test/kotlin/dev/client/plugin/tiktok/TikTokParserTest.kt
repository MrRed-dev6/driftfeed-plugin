package dev.client.plugin.tiktok

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор проверяется на тех формах, которые TikTok реально отдаёт в `/embed`.
 * Если он переименует поля, сломается именно этот тест, а не пользователь в ленте.
 */
class TikTokParserTest {

    @Test
    fun `элемент ленты разбирается с автором из профиля`() {
        val json = JSONObject(
            """
            {
              "id": "7707063215498399006",
              "desc": "котик",
              "coverUrl": "https://cdn/c.jpg",
              "playAddr": "https://cdn/v.mp4",
              "playCount": 132800,
              "authorUniqueId": "tiktok"
            }
            """.trimIndent()
        )
        val author = parseAuthor(JSONObject("""{"id":"107955","uniqueId":"tiktok","nickname":"TikTok"}"""))

        val item = assertNotNull(parsePlaylistItem(json, author))
        assertEquals("7707063215498399006", item.id)
        assertEquals("https://cdn/v.mp4", item.playUrl)
        assertEquals("https://cdn/c.jpg", item.coverUrl)
        assertEquals("котик", item.description)
        assertEquals("tiktok", item.author.username)
        assertEquals("TikTok", item.author.displayName)
    }

    @Test
    fun `без профиля автор собирается из ника в элементе`() {
        val json = JSONObject(
            """{"id":"1","playAddr":"https://cdn/v.mp4","authorUniqueId":"catlover"}"""
        )
        val item = assertNotNull(parsePlaylistItem(json, null))
        assertEquals("catlover", item.author.id)
        assertEquals("catlover", item.author.username)
    }

    @Test
    fun `элемент без id отбрасывается`() {
        assertNull(parsePlaylistItem(JSONObject("""{"desc":"без id"}"""), null))
    }

    @Test
    fun `видео из videoData разбирается со статистикой`() {
        val infos = JSONObject(
            """
            {
              "id": "7707063215498399006",
              "text": "описание",
              "covers": ["https://cdn/c.jpg", "https://cdn/c2.jpg"],
              "video": { "urls": ["https://cdn/v.mp4"] },
              "diggCount": 2463,
              "commentCount": 588
            }
            """.trimIndent()
        )
        val author = JSONObject(
            """
            {
              "uniqueId": "tiktok",
              "userId": "107955",
              "nickName": "TikTok",
              "covers": ["https://cdn/a.jpg"]
            }
            """.trimIndent()
        )

        val item = assertNotNull(parseEmbedVideo(infos, author))
        assertEquals("7707063215498399006", item.id)
        assertEquals("https://cdn/v.mp4", item.playUrl)
        assertEquals("https://cdn/c.jpg", item.coverUrl)
        assertEquals("описание", item.description)
        assertEquals(2463L, item.likeCount)
        assertEquals(588L, item.commentCount)
        assertEquals("tiktok", item.author.username)
        assertEquals("https://cdn/a.jpg", item.author.avatarUrl)
    }

    @Test
    fun `числа, пришедшие строкой, тоже читаются`() {
        val infos = JSONObject(
            """{"id":"1","video":{"urls":["https://cdn/v.mp4"]},"diggCount":"1200","commentCount":"34"}"""
        )
        val item = assertNotNull(parseEmbedVideo(infos, null))
        assertEquals(1200L, item.likeCount)
        assertEquals(34L, item.commentCount)
    }

    @Test
    fun `видео без ссылки на поток остаётся, но с пустым playUrl`() {
        val item = assertNotNull(parseEmbedVideo(JSONObject("""{"id":"1"}"""), null))
        assertEquals("", item.playUrl)
    }

    @Test
    fun `профиль берёт ник как id, чтобы в него можно было сходить повторно`() {
        val author = parseAuthor(
            JSONObject(
                """
                {
                  "id": "107955",
                  "uniqueId": "tiktok",
                  "nickname": "TikTok",
                  "avatarThumbUrl": "https://cdn/a.jpg"
                }
                """.trimIndent()
            )
        )
        assertEquals("tiktok", author.id)
        assertEquals("tiktok", author.username)
        assertEquals("TikTok", author.displayName)
        assertEquals("https://cdn/a.jpg", author.avatarUrl)
    }

    @Test
    fun `поисковый запрос превращается в хештег`() {
        assertEquals("catvideos", hashtagOf("cat videos"))
        assertEquals("fyp", hashtagOf("#FYP"))
        assertEquals("котики", hashtagOf(" котики "))
        assertEquals("", hashtagOf("   "))
    }

    @Test
    fun `ник вытаскивается из ссылки и собаки`() {
        assertEquals("tiktok", usernameOf("@TikTok"))
        assertEquals("tiktok", usernameOf("https://www.tiktok.com/@tiktok"))
        assertEquals("tiktok", usernameOf("tiktok"))
        assertTrue(usernameOf("").isEmpty())
    }

    @Test
    fun `комментарий TikTok разбирается с аватаром в snake_case`() {
        val json = JSONObject(
            """
            {
              "cid": "7707082245149147934",
              "aweme_id": "7707063215498399006",
              "text": "yo tiktok appeal my shi",
              "create_time": 1794444956,
              "digg_count": 43,
              "reply_comment_total": 4,
              "user": {
                "uid": "7638965647167685646",
                "unique_id": "mastantuono23467",
                "nickname": "mastantuono goat",
                "avatar_thumb": {
                  "uri": "tos-maliva-avt-0068/1ecb",
                  "url_list": ["https://p16-common-sign.tiktokcdn.com/avt.jpg"]
                }
              }
            }
            """.trimIndent()
        )

        val comment = assertNotNull(parseOfficialComment(json))
        assertEquals("7707082245149147934", comment.id)
        assertEquals("yo tiktok appeal my shi", comment.text)
        assertEquals(43L, comment.likeCount)
        assertEquals(4L, comment.replyCount)
        assertEquals("mastantuono23467", comment.author.username)
        assertEquals("mastantuono goat", comment.author.displayName)
        assertEquals("https://p16-common-sign.tiktokcdn.com/avt.jpg", comment.author.avatarUrl)
    }
}
