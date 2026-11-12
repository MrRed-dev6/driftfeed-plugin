package dev.client.plugin.tiktok

/**
 * Настройки провайдера.
 *
 * Меняются не из клиента, а выпуском новой версии плагина: в этой архитектуре
 * version в sources.json обновляется без релиза приложения, так что это дешёво.
 * Добавлять метод configure() в ProviderContext пришлось бы через общий контракт,
 * а его версионирует сторона клиента — не наша.
 */
internal data class TikTokConfig(
    val userAgent: String = DEFAULT_USER_AGENT,
    val region: String = "US",
    val language: String = "en",
    val baseUrl: String = "https://www.tiktok.com",
    /** Дополнительные заголовки, если понадобятся конкретной версии эндпоинтов. */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
