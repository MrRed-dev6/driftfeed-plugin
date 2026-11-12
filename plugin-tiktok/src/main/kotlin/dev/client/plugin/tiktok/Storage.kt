package dev.client.plugin.tiktok

import dev.client.provider.ProviderContext

/**
 * Хранилище сессии.
 *
 * Клиент даёт плагину key-value хранилище (`ProviderContext.getStorage`), и
 * нужно оно ровно для одного: чтобы `msToken` и `tt_webid_v2` переживали
 * перезапуск приложения. Без этого каждый старт выглядит как первый визит
 * нового анонимного клиента, а такой трафик отсекается охотнее.
 *
 * Свой интерфейс, а не прямой вызов `context.getStorage`, по двум причинам:
 * тесты обходятся без фейкового `ProviderContext`, и `NoStorage` позволяет
 * собрать сессию там, где контекста нет.
 */
internal interface Storage {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
}

/** Клиент без хранилища: сессия живёт только в памяти. */
internal object NoStorage : Storage {
    override suspend fun get(key: String): String? = null
    override suspend fun put(key: String, value: String) = Unit
}

/**
 * Хранилище клиента.
 *
 * Ключевая деталь: методов `getStorage`/`setStorage` в клиенте может не быть.
 * Плагин обновляется независимо от приложения, поэтому новая его версия легко
 * попадает на сборку клиента, где этих методов ещё нет. В этом случае ловим
 * `NoSuchMethodError` и откатываемся к сессии в памяти — а не падаем на первом
 * же запросе. Деградация тихая, но заметная: в логе одна строка.
 */
internal class ProviderStorage(private val context: ProviderContext) : Storage {

    @Volatile
    private var warned = false

    override suspend fun get(key: String): String? = try {
        context.getStorage(key)
    } catch (e: NoSuchMethodError) {
        warnMissing()
        null
    } catch (e: Throwable) {
        // Битый файл, нет прав, кончилось место — сессия не настолько важна,
        // чтобы из-за неё ломать ленту.
        context.log(TAG, "getStorage($key): ${e.message}")
        null
    }

    override suspend fun put(key: String, value: String) {
        try {
            context.setStorage(key, value)
        } catch (e: NoSuchMethodError) {
            warnMissing()
        } catch (e: Throwable) {
            context.log(TAG, "setStorage($key): ${e.message}")
        }
    }

    private fun warnMissing() {
        if (warned) return
        warned = true
        context.log(
            TAG,
            "в клиенте нет getStorage/setStorage — сессия не сохранится между запусками",
        )
    }

    private companion object {
        const val TAG = "TikTokStorage"
    }
}
