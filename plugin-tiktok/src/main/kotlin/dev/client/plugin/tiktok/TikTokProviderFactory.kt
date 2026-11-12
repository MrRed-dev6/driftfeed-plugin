package dev.client.plugin.tiktok

import dev.client.provider.ContractVersion
import dev.client.provider.Provider
import dev.client.provider.ProviderContext
import dev.client.provider.ProviderFactory

/**
 * Точка входа плагина. Имя этого класса идёт в sources.json как `entryClass`.
 * Конструктор обязан быть публичным и без аргументов.
 */
class TikTokProviderFactory : ProviderFactory {
    override fun contractMajor(): Int = ContractVersion.MAJOR
    override fun create(context: ProviderContext): Provider = TikTokProvider(context)
}
