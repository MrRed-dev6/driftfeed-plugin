package dev.client.plugin.tiktok

/**
 * Настройки провайдера.
 *
 * Меняются не из клиента, а выпуском новой версии плагина: в этой архитектуре
 * version в sources.json обновляется без релиза приложения, так что это дёшево.
 * Добавлять метод configure() в ProviderContext пришлось бы через общий контракт,
 * а его версионирует сторона клиента — не наша.
 */
internal data class TikTokConfig(
    val userAgent: String = DEFAULT_USER_AGENT,
    val region: String = "US",
    val language: String = "en",
    val baseUrl: String = "https://www.tiktok.com",
    /**
     * Раздел `/embed` — часть сайта TikTok, которая отдаёт данные обычным HTML
     * без подписи `X-Bogus`/`X-Gnarly`: профиль, видео, страница хештега.
     */
    val embedPath: String = "/embed",
    /**
     * Публичный шлюз, у которого есть лента и комментарии: у самого TikTok эти
     * два раздела закрыты подписью браузерного SDK, а `/embed` их не отдаёт.
     */
    val gatewayUrl: String = "https://www.tikwm.com",
    /** Сколько элементов просить у шлюза за раз: больше 30 он не отдаёт. */
    val feedPageSize: Int = 20,
    val commentPageSize: Int = 20,
    /** Дополнительные заголовки, если понадобятся конкретной версии эндпоинтов. */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
