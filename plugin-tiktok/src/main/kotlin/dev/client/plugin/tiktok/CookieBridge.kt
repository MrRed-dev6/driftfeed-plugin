package dev.client.plugin.tiktok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Чтение cookie из системной банки Android — той самой, куда пишет `WebView`.
 *
 * Зачем так. Сессию аккаунта плагину никто не передаёт: клиент отказался
 * и подставлять cookie в запросы, и писать её в хранилище плагина. Но
 * `CookieManager` — публичный API Android, один на процесс: плагин грузится
 * в тот же процесс через `DexClassLoader`, поэтому может прочитать банку сам.
 * Клиент при этом не пишет ничего и не знает, что его читают.
 *
 * Это не обход: читается ровно то, что пользователь сам открыл в браузере
 * приложения, и только для домена TikTok. Никаких приватных API, никакого
 * доступа к чужим приложениям — `android.webkit.CookieManager` доступен любому
 * коду в процессе, как и `Log` или `System`.
 *
 * Классы Android подключаются через `Class.forName`, а не напрямую: модуль
 * плагина — обычный `kotlin("jvm")`, `android.jar` в его classpath нет, и
 * тащить туда 13-мегабайтный стаб ради двух вызовов незачем. На устройстве
 * классы есть всегда — их даёт загрузчик приложения-хоста.
 *
 * `getInstance()` положено вызывать на главном потоке, поэтому основной путь —
 * `Dispatchers.Main`. Если главного диспетчера нет (клиент собрался без
 * `kotlinx-coroutines-android`), остаётся прямой вызов: он работает на
 * большинстве версий, а если нет — вернётся `null`, и плагин просто останется
 * на шлюзе.
 */
internal object CookieBridge {

    private const val MANAGER = "android.webkit.CookieManager"

    /**
     * Строка `Cookie` для адреса, или `null` — банка пуста, недоступна или
     * пользователь не входил. Пустая строка тоже `null`: для TikTok это то же
     * самое, что её отсутствие.
     */
    suspend fun read(url: String): String? {
        direct(url)?.let { return it }
        return onMain(url)
    }

    private fun direct(url: String): String? =
        try {
            raw(url)
        } catch (e: Throwable) {
            // Чаще всего это «не на главном потоке» — им займётся onMain.
            null
        }

    private suspend fun onMain(url: String): String? =
        try {
            withContext(Dispatchers.Main) { raw(url) }
        } catch (e: Throwable) {
            null
        }

    private fun raw(url: String): String? {
        val type = Class.forName(MANAGER)
        val instance = type.getMethod("getInstance").invoke(null) ?: return null
        val cookies = type.getMethod("getCookie", String::class.java)
            .invoke(instance, url) as? String
        return cookies?.takeIf { it.isNotBlank() }
    }
}
