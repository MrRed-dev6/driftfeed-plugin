package dev.client.plugin.tiktok

import dev.client.provider.ProviderContext

/**
 * Подпись `X-Gnarly` — то, без чего веб-API TikTok не отвечает.
 *
 * Считается не на Kotlin, а на JS в движке клиента (`ProviderContext.evalJs`):
 * алгоритм целиком завязан на байтовые преобразования браузерного SDK, и
 * переносить его в Kotlin значило бы завести вторую реализацию, которую
 * придётся сверять с первой при каждом обновлении TikTok. Здесь же исполняется
 * та же сборка `abogus`, что проверена в браузере, — см. [SignerScript].
 *
 * Скрипт грузится один раз: движок в клиенте живёт между вызовами, поэтому
 * `__signer` остаётся в глобальной области и следующие подписи стоят ~3 мс.
 * Если клиент пересоздал движок (обновление плагина, нехватка памяти),
 * `__signer` пропадает — тогда скрипт грузится заново и подпись повторяется.
 */
internal class TikTokSigner(private val context: ProviderContext) {

    /** Загружен ли скрипт в текущий движок. */
    private var ready = false

    /** Кэш раскодированного исходника: base64 → строка стоит один раз на процесс. */
    private var source: String? = null

    /**
     * Подпись для строки запроса. `null` — подписать не вышло (движок недоступен,
     * скрипт не разобрался). Это не ошибка: вызывающий просто отправляет запрос
     * без заголовка — эндпоинт ленты сегодня отвечает и так.
     *
     * `query` должен быть ровно тем, что уйдёт в URL после `?`: TikTok считает
     * подпись по строке как есть, и любое расхождение в порядке или кодировке
     * параметров делает её недействительной.
     */
    suspend fun sign(query: String, userAgent: String): String? {
        if (!ready && !load()) return null
        evaluate(query, userAgent, logFailure = false)?.let { return it }
        // Движок мог быть пересоздан клиентом — грузим скрипт ещё раз.
        // Молча: первая неудача — это штатная проверка «движок тот же?».
        ready = false
        if (!load()) return null
        return evaluate(query, userAgent, logFailure = true)
    }

    private suspend fun evaluate(query: String, userAgent: String, logFailure: Boolean): String? =
        try {
            context.evalJs(SIGN_CALL, mapOf("QS" to query, "UA" to userAgent))
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            if (logFailure) context.log(TAG, "подпись не посчиталась: ${e.message}")
            null
        }

    private suspend fun load(): Boolean =
        try {
            context.evalJs(script(), emptyMap())
            ready = true
            true
        } catch (e: Throwable) {
            context.log(TAG, "движок не поднял скрипт подписи: ${e.message}")
            false
        }

    private fun script(): String = source ?: SignerScript.source.also { source = it }

    private companion object {
        const val TAG = "TikTokSigner"

        /**
         * Вызов считается в том же движке, где уже лежит `__signer`. Пустая
         * строка вторым аргументом — это `body`: для GET его нет.
         */
        const val SIGN_CALL = """new __signer.XGnarly().sign(QS, "", UA)"""
    }
}
