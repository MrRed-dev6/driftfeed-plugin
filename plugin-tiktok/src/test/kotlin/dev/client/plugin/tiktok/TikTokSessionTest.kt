package dev.client.plugin.tiktok

import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Хранилище подменяется фейком: тестируется логика восстановления сессии,
 * а не клиентский файл.
 */
class TikTokSessionTest {

    private class FakeStorage(initial: Map<String, String> = emptyMap()) : Storage {
        val values = LinkedHashMap(initial)
        var reads = 0
            private set
        var writes = 0
            private set

        override suspend fun get(key: String): String? {
            reads++
            return values[key]
        }

        override suspend fun put(key: String, value: String) {
            writes++
            values[key] = value
        }
    }

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789=_-"

    @Test
    fun `msToken нужной длины и из base64url`() {
        val token = TikTokSession().msToken
        assertEquals(128, token.length)
        assertTrue(token.all { it in alphabet }, "недопустимый символ в msToken: $token")
    }

    @Test
    fun `две сессии дают разные токены`() {
        val random = SecureRandom()
        assertNotEquals(TikTokSession(random = random).msToken, TikTokSession(random = random).msToken)
    }

    @Test
    fun `webId похож на настоящий`() {
        val webId = TikTokSession().webId
        assertEquals(19, webId.length)
        assertTrue(webId.startsWith("7"))
        assertTrue(webId.all { it.isDigit() })
    }

    @Test
    fun `cookieHeader содержит оба значения`() {
        val session = TikTokSession()
        assertEquals(
            "msToken=${session.msToken}; tt_webid_v2=${session.webId}",
            session.cookieHeader(),
        )
    }

    @Test
    fun `restore подхватывает сохранённую сессию`() = runBlocking {
        val storage = FakeStorage(
            mapOf(
                "session.msToken" to "a".repeat(128),
                "session.webId" to "7123456789012345678",
            )
        )
        val session = TikTokSession(storage)
        session.restore()

        assertEquals("a".repeat(128), session.msToken)
        assertEquals("7123456789012345678", session.webId)
        // Всё уже было — перезаписывать нечего.
        assertEquals(0, storage.writes)
    }

    @Test
    fun `restore сохраняет новую сессию`() = runBlocking {
        val storage = FakeStorage()
        val session = TikTokSession(storage)
        session.restore()

        assertEquals(session.msToken, storage.values["session.msToken"])
        assertEquals(session.webId, storage.values["session.webId"])
        assertEquals(2, storage.writes)
    }

    @Test
    fun `restore заменяет мусор в хранилище`() = runBlocking {
        val storage = FakeStorage(
            mapOf(
                "session.msToken" to "короткий",
                "session.webId" to "123",
            )
        )
        val session = TikTokSession(storage)
        session.restore()

        assertEquals(128, session.msToken.length)
        assertEquals(19, session.webId.length)
        assertEquals(session.msToken, storage.values["session.msToken"])
        assertEquals(session.webId, storage.values["session.webId"])
    }

    @Test
    fun `повторный restore не читает хранилище`() = runBlocking {
        val storage = FakeStorage()
        val session = TikTokSession(storage)
        session.restore()
        val readsAfterFirst = storage.reads

        session.restore()

        assertEquals(readsAfterFirst, storage.reads)
    }

    @Test
    fun `без хранилища сессия работает в памяти`() = runBlocking {
        val session = TikTokSession(NoStorage)
        session.restore()

        assertEquals(128, session.msToken.length)
        assertEquals(19, session.webId.length)
    }
}
