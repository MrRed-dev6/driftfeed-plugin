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
     * Публичный шлюз, у которого есть лента: у самого TikTok она закрыта
     * подписью браузерного SDK, а `/embed` её не отдаёт.
     */
    val gatewayUrl: String = "https://www.tikwm.com",
    /**
     * Официальная лента TikTok. Работает только с подписью `X-Gnarly` и cookie
     * вошедшего аккаунта; без cookie отвечает пустым списком, и тогда лента
     * берётся из шлюза — см. [TikTokApi.feed].
     */
    val recommendPath: String = "/api/recommend/item_list/",
    /** Сколько элементов просить за раз: шлюз больше 30 не отдаёт. */
    val feedPageSize: Int = 20,
    val commentPageSize: Int = 20,
    /**
     * Поисковик, через индекс которого ищется текст: поиск самого TikTok закрыт
     * подписью, поэтому слова ищутся по проиндексированным страницам видео.
     */
    val searchEngineUrl: String = "https://lite.duckduckgo.com",
    val searchSite: String = "tiktok.com/video",
    /** Сколько найденных видео доразбирать: каждое — отдельный запрос страницы. */
    val searchDetailLimit: Int = 5,
    /** Дополнительные заголовки, если понадобятся конкретной версии эндпоинтов. */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
