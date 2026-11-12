package dev.client.plugin.tiktok

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/**
 * Анонимная сессия плагина.
 *
 * Почему генерируем, а не принимаем: `ProviderContext` отдаёт только тело
 * ответа — ни заголовков, ни cookie jar. Значит `Set-Cookie` плагин не увидит
 * никогда и принять `msToken` от сервера не может.
 *
 * Хорошая новость: `msToken` и есть клиентское значение. Веб-клиент TikTok
 * генерирует его сам и отправляет одновременно параметром URL и кукой.
 * Плагин делает то же самое, а в [Storage] кладёт результат, чтобы при
 * следующем запуске представиться тем же клиентом, а не новым.
 *
 * Если хранилища нет (старый клиент), сессия просто живёт в памяти.
 */
internal class TikTokSession(
    private val storage: Storage = NoStorage,
    private val random: SecureRandom = SecureRandom(),
) {

    private val mutex = Mutex()

    @Volatile
    private var restored = false

    @Volatile
    var msToken: String = newToken()
        private set

    /** Похож на настоящий tt_webid_v2: ведущая семёрка и 18 цифр. */
    @Volatile
    var webId: String = newWebId()
        private set

    fun cookieHeader(): String = "msToken=$msToken; tt_webid_v2=$webId"

    /**
     * Подхватывает сохранённую сессию. Вызывается перед первым запросом;
     * повторные вызовы упираются в флаг и ничего не читают.
     *
     * Сохранённое значение проверяется, а не принимается на веру: файл мог
     * остаться от прерванной записи или от прошлой версии плагина с другим
     * форматом. Мусор молча заменяется новым значением.
     */
    suspend fun restore() {
        if (restored) return
        mutex.withLock {
            if (restored) return@withLock

            val savedToken = storage.get(KEY_MS_TOKEN)
            val savedWebId = storage.get(KEY_WEB_ID)

            if (savedToken != null && isToken(savedToken)) msToken = savedToken
            if (savedWebId != null && isWebId(savedWebId)) webId = savedWebId

            // Пишем только то, чего в хранилище не было или что не прошло проверку.
            if (msToken != savedToken) storage.put(KEY_MS_TOKEN, msToken)
            if (webId != savedWebId) storage.put(KEY_WEB_ID, webId)

            restored = true
        }
    }

    private fun newToken(): String = buildString(TOKEN_LENGTH) {
        repeat(TOKEN_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    private fun newWebId(): String = buildString(WEB_ID_LENGTH) {
        append('7')
        repeat(WEB_ID_LENGTH - 1) { append('0' + random.nextInt(10)) }
    }

    private fun isToken(value: String): Boolean =
        value.length == TOKEN_LENGTH && value.all { it in ALPHABET }

    private fun isWebId(value: String): Boolean =
        value.length == WEB_ID_LENGTH && value[0] == '7' && value.all { it.isDigit() }

    private companion object {
        const val KEY_MS_TOKEN = "session.msToken"
        const val KEY_WEB_ID = "session.webId"

        const val TOKEN_LENGTH = 128
        const val WEB_ID_LENGTH = 19

        /** Алфавит base64url: именно такой набор символов TikTok ожидает в msToken. */
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789=_-"
    }
}
