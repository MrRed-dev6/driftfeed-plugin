package dev.client.plugin.tiktok

import dev.client.provider.ProviderContext
import dev.client.provider.ProviderError

/**
 * Подпись запросов TikTok.
 *
 * TikTok отдаёт данные только если в URL есть актуальные параметры подписи
 * (`msToken`, `X-Bogus` и то, что придёт им на смену). Считает их JavaScript
 * самого TikTok, поэтому плагин передаёт скрипт в JS-движок клиента
 * через `ProviderContext.evalJs` и забирает готовый URL обратно.
 *
 * Скрипт SDK лежит ресурсом внутри jar плагина (`/tiktok/webmssdk.js`).
 * Так его можно обновить выпуском новой версии плагина — без релиза клиента,
 * ровно для этого в `sources.json` и есть поле `version`.
 */
internal interface Signer {
    /** Можно ли вообще подписывать запросы. Клиент показывает UI по этому флагу. */
    val available: Boolean

    /** Возвращает URL с добавленными параметрами подписи. */
    suspend fun sign(url: String, userAgent: String, msToken: String): String
}

/** Клиент без JS-движка либо плагин без SDK: подписи нет, запросы уйдут как есть. */
internal object NoSigner : Signer {
    override val available: Boolean = false
    override suspend fun sign(url: String, userAgent: String, msToken: String): String = url
}

internal class JsSigner(
    private val context: ProviderContext,
    private val sdkPath: String = DEFAULT_SDK_PATH,
) : Signer {

    @Volatile
    private var sdkScript: String? = null

    private val hasSdkResource: Boolean by lazy {
        JsSigner::class.java.classLoader?.getResource(sdkPath.removePrefix("/")) != null
    }

    /**
     * Блок «шим + SDK» собирается один раз: он весит сотни килобайт,
     * и пересобирать его на каждый запрос незачем.
     */
    private val sdkPrefix: String? by lazy {
        val sdk = loadSdk() ?: return@lazy null
        buildString {
            append("if (globalThis.__tiktokSdkLoaded !== true) {\n")
            append(BROWSER_SHIM).append('\n')
            append(sdk).append('\n')
            append("globalThis.__tiktokSdkLoaded = true;\n}\n")
        }
    }

    override val available: Boolean
        get() = hasSdkResource

    override suspend fun sign(url: String, userAgent: String, msToken: String): String {
        val prefix = sdkPrefix ?: throw ProviderError.Unknown(
            "В плагине нет $sdkPath — подпись запросов недоступна"
        )

        // Движок в клиенте живёт между вызовами, поэтому SDK грузится один раз
        // на экземпляр движка. Флаг __tiktokSdkLoaded ставится в самом JS:
        // если клиент пересоздаст движок, скрипт просто загрузит SDK заново.
        //
        // document.cookie выставляем на каждый вызов: SDK читает msToken оттуда,
        // а не только из URL, поэтому значение должно быть актуальным.
        val script = prefix +
            "if (globalThis.__ms) { globalThis.document.cookie = 'msToken=' + globalThis.__ms; }\n" +
            "__tiktokSign(__url, __ua);"

        val result = runCatching {
            context.evalJs(
                script,
                mapOf("__url" to url, "__ua" to userAgent, "__ms" to msToken),
            )
        }.getOrElse { throw ProviderError.Unknown("JS-подпись упала: ${it.message}") }.trim()

        if (result.isEmpty() || result == "undefined" || result == "null") {
            throw ProviderError.Unknown("JS-подпись вернула пустой результат")
        }
        return result
    }

    private fun loadSdk(): String? {
        sdkScript?.let { return it.ifEmpty { null } }
        val text = runCatching {
            JsSigner::class.java.getResourceAsStream(sdkPath)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        sdkScript = text ?: ""
        return text?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_SDK_PATH = "/tiktok/webmssdk.js"

        /**
         * Минимальное браузерное окружение: у QuickJS нет ни window, ни document,
         * а SDK TikTok на них опирается. Плюс здесь ставится `__tiktokSign` —
         * единственная функция, которую вызывает плагин.
         */
        val BROWSER_SHIM = """
            (function () {
              if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
              if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;
              if (typeof globalThis.document === 'undefined') {
                globalThis.document = {
                  createElement: function () { return { style: {}, appendChild: function () {} }; },
                  getElementsByTagName: function () { return []; },
                  addEventListener: function () {},
                  removeEventListener: function () {},
                  documentElement: { style: {} },
                  cookie: ''
                };
              }
              if (typeof globalThis.navigator === 'undefined') {
                globalThis.navigator = {
                  userAgent: '', platform: 'Linux aarch64',
                  language: 'en-US', languages: ['en-US'], cookieEnabled: true
                };
              }
              if (typeof globalThis.location === 'undefined') {
                globalThis.location = {
                  href: 'https://www.tiktok.com/', protocol: 'https:',
                  host: 'www.tiktok.com', hostname: 'www.tiktok.com', search: ''
                };
              }
              if (typeof globalThis.__tiktokSign === 'function') return;
              globalThis.__tiktokSign = function (url, userAgent) {
                if (globalThis.navigator) globalThis.navigator.userAgent = userAgent || '';
                var sdk = globalThis.byted_acrawler || globalThis._bytedAcrawler;
                if (sdk && typeof sdk.frontierSign === 'function') {
                  var signed = sdk.frontierSign({ url: url, userAgent: userAgent });
                  var xb = (signed && (signed['X-Bogus'] || signed.XBogus)) || '';
                  if (!xb) return url;
                  return url + (url.indexOf('?') === -1 ? '?' : '&')
                    + 'X-Bogus=' + encodeURIComponent(xb);
                }
                return url;
              };
            })();
        """.trimIndent()
    }
}
