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
     * Раздел `/embed` — единственная часть сайта TikTok, которая отдаёт данные
     * обычным HTML без подписи `X-Bogus`/`X-Gnarly`. Всё остальное (`/api/*`,
     * лента, поиск, комментарии) закрыто и требует подписи из браузерного SDK.
     */
    val embedPath: String = "/embed",
    /**
     * Лента собирается из популярных хештегов: открытой ленты без подписи нет,
     * а страница хештега отдаёт 10 видео. Курсор ленты — номер хештега в списке,
     * поэтому прокрутка идёт по кругу и не упирается в конец.
     */
    val feedTags: List<String> = listOf("fyp", "viral", "trending", "foryou", "foryoupage"),
    /** Дополнительные заголовки, если понадобятся конкретной версии эндпоинтов. */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
