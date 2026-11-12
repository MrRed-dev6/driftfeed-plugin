package dev.client.provider

/**
 * Плагин реализует этот интерфейс в классе с публичным конструктором без аргументов.
 * Имя класса передаётся в plugin.json (entryClass), клиент создаёт его через рефлексию
 * после загрузки DEX и сразу проверяет contractMajor().
 */
interface ProviderFactory {
    /** Должна совпадать с ContractVersion.MAJOR на момент сборки плагина. */
    fun contractMajor(): Int

    fun create(context: ProviderContext): Provider
}

/**
 * То немногое, что клиент даёт провайдеру самостоятельно делать сеть/ввод-вывод.
 * Плагин не получает доступ к остальному приложению.
 */
interface ProviderContext {
    /**
     * Бросает ProviderError.RateLimited на HTTP 429, ProviderError.NeedsLogin на 401/403,
     * ProviderError.Unavailable на прочие ошибки сети/статуса — вместо голого IOException,
     * чтобы плагин мог реагировать осмысленно, а не просто ловить общее исключение.
     */
    suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): ByteArray
    suspend fun httpPost(url: String, body: ByteArray, headers: Map<String, String> = emptyMap()): ByteArray
    fun log(tag: String, message: String)

    /**
     * Выполняет JS-код в движке, который держит клиент (QuickJS), и возвращает результат
     * как строку (обычно JSON, который плагин сам разбирает). Клиент предоставляет только
     * исполнитель — какой скрипт передавать и какое окружение (globals) ему смоделировать,
     * решает плагин. suspend — выполнение уходит в фоновый поток, не блокирует вызывающий.
     */
    suspend fun evalJs(script: String, globals: Map<String, String> = emptyMap()): String

    /**
     * Непрозрачное хранилище "ключ → строка", отдельное для каждого плагина.
     * Клиент не знает и не проверяет, что именно в нём лежит — это дело плагина.
     * Значение переживает перезапуск приложения. Лимит на размер значения — см. реализацию.
     * Возвращает null, если ключа ещё нет (это нормальный случай, а не ошибка).
     */
    suspend fun getStorage(key: String): String?

    /** Бросает IllegalArgumentException, если value превышает лимит размера. */
    suspend fun setStorage(key: String, value: String)
}
