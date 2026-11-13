package dev.client.plugin.tiktok

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Разбор ответов шлюза. Формы взяты из настоящих ответов `tikwm.com`, включая
 * неудобные случаи: `hdplay: null`, `title: null`, фотопост без потока.
 */
class TikTokGatewayParseTest {

    @Test
    fun `элемент ленты шлюза разбирается целиком`() {
        val json = JSONObject(
            """
            {
              "video_id": "7697903190984887584",
              "region": "GB",
              "title": "Hot Fork Vs Milk #hotspoon",
              "cover": "https://p19/c.webp",
              "origin_cover": "https://p16/o.webp",
              "duration": 11,
              "play": "https://v45/video.mp4",
              "wmplay": "https://v45/wm.mp4",
              "hdplay": null,
              "play_count": 1000,
              "digg_count": 42,
              "comment_count": 7,
              "create_time": 1792307761,
              "author": {
                "id": "107955",
                "unique_id": "tiktok",
                "nickname": "TikTok",
                "avatar": "https://p16/a.webp"
              }
            }
            """.trimIndent()
        )

        val item = assertNotNull(parseGatewayItem(json))
        assertEquals("7697903190984887584", item.id)
        // play — поток без водяного знака, он важнее wmplay.
        assertEquals("https://v45/video.mp4", item.playUrl)
        assertEquals("https://p19/c.webp", item.coverUrl)
        assertEquals("Hot Fork Vs Milk #hotspoon", item.description)
        assertEquals(42L, item.likeCount)
        assertEquals(7L, item.commentCount)
        assertEquals("tiktok", item.author.username)
        assertEquals("TikTok", item.author.displayName)
        assertEquals("https://p16/a.webp", item.author.avatarUrl)
    }

    @Test
    fun `без play берётся hdplay, а фотопост остаётся без потока`() {
        val hd = JSONObject(
            """{"video_id":"1","hdplay":"https://v45/hd.mp4","author":{"unique_id":"a"}}"""
        )
        assertEquals("https://v45/hd.mp4", assertNotNull(parseGatewayItem(hd)).playUrl)

        val photo = JSONObject(
            """{"video_id":"2","title":null,"play":null,"hdplay":null,"wmplay":null}"""
        )
        val item = assertNotNull(parseGatewayItem(photo))
        assertEquals("", item.playUrl)
        assertEquals("", item.description)
    }

    @Test
    fun `комментарий шлюза разбирается с автором и счётчиками`() {
        val json = JSONObject(
            """
            {
              "id": "7707082245149147934",
              "video_id": "7707063215498399006",
              "text": "yo tiktok appeal my shi",
              "images": [],
              "create_time": 1794444956,
              "digg_count": 43,
              "reply_total": 4,
              "user": {
                "id": "7638965647167685646",
                "region": "US",
                "sec_uid": "MS4wLjABAAAA",
                "unique_id": "mastantuono23467",
                "nickname": "mastantuono goat",
                "avatar": "https://p16/avt.webp"
              },
              "status": 1
            }
            """.trimIndent()
        )

        val comment = assertNotNull(parseComment(json))
        assertEquals("7707082245149147934", comment.id)
        assertEquals("yo tiktok appeal my shi", comment.text)
        assertEquals(43L, comment.likeCount)
        assertEquals(4L, comment.replyCount)
        assertEquals("mastantuono23467", comment.author.username)
        assertEquals("mastantuono goat", comment.author.displayName)
        assertEquals("https://p16/avt.webp", comment.author.avatarUrl)
    }

    @Test
    fun `комментарий без текста не теряется`() {
        val json = JSONObject("""{"id":"5","text":null,"user":{"unique_id":"x"}}""")
        val comment = assertNotNull(parseComment(json))
        assertEquals("", comment.text)
        assertEquals("x", comment.author.username)
    }

    @Test
    fun `кандидаты хештегов для фразы и одиночного слова`() {
        assertEquals(listOf("catvideos", "videos"), hashtagCandidates("cat videos"))
        assertEquals(listOf("fyp"), hashtagCandidates("#FYP"))
        // Хештег из всей фразы почти наверняка пуст, поэтому вторым идёт
        // самое длинное слово: по нему видео находятся.
        assertEquals(listOf("смешныекоты", "смешные"), hashtagCandidates("смешные коты"))
        assertTrue(hashtagCandidates("   ").isEmpty())
    }
}
