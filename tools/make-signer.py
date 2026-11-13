#!/usr/bin/env python3
"""Пересобирает SignerScript.kt из tools/signer/signer.js.

Зачем отдельный шаг: 34 КБ JS в Kotlin-файле руками не поддерживают — при
обновлении `abogus` надо заново собрать `signer.js` (как это делается, написано
в tools/signer/README.md), а потом запустить этот скрипт. Он кодирует JS в
base64 и режет на куски: одна строка такого размера упирается в лимит длины
литерала в пуле констант dex.

    python3 tools/make-signer.py
"""

import base64
import pathlib
import textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "tools" / "signer" / "signer.js"
TARGET = (
    ROOT / "plugin-tiktok" / "src" / "main" / "kotlin" / "dev" / "client" / "plugin" / "tiktok"
    / "SignerScript.kt"
)

# Размер куска в символах base64. Лимит литерала в dex — 64 КБ, но куски
# берём с большим запасом: так файл читаемо листается в диффе.
CHUNK = 2000

HEAD = '''package dev.client.plugin.tiktok

/**
 * Скрипт подписи `X-Gnarly` — тот, что исполняет [TikTokSigner] через
 * `ProviderContext.evalJs`.
 *
 * Здесь он лежит строкой в base64, а не файлом в jar: классы плагина грузятся
 * через `DexClassLoader`, и ресурсы из jar он не отдаёт — `getResourceAsStream`
 * возвращает null. Строка в dex работает всегда.
 *
 * Что внутри: сборка `abogus` 2.1.1 (MIT, © 2024 sobird) — чистый JS без
 * зависимостей, который считает `X-Gnarly` так же, как это делает браузерный
 * SDK TikTok. Экспорты ESM заменены на один объект `__signer`, сверху добавлены
 * заглушки `TextEncoder`/`TextDecoder`: в QuickJS их нет, а код их создаёт при
 * загрузке. `crypto` не нужен — `abogus` проверяет его наличие и откатывается
 * на `Math.random`.
 *
 * Проверено на том же QuickJS 2021-03-27, что стоит в клиенте: подпись
 * считается за ~3 мс и совпадает по длине с эталонной (300 символов).
 *
 * Текст лицензии — `LICENSES/abogus-MIT.txt`. Файл собран скриптом
 * `tools/make-signer.py` из `tools/signer/signer.js`; править его руками
 * бессмысленно — следующая сборка перезапишет.
 */
internal object SignerScript {

    /** Исходник подписи целиком: base64 → UTF-8. */
    val source: String by lazy {
        String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    /** Куски склеиваются в рантайме — см. [CHUNKS]. */
    private val encoded: String by lazy { CHUNKS.joinToString("") }

    private val CHUNKS: List<String> = listOf(
'''

TAIL = '''
    )
}
'''


def main() -> None:
    js = SOURCE.read_text(encoding="utf-8")
    encoded = base64.b64encode(js.encode("utf-8")).decode("ascii")
    chunks = textwrap.wrap(encoded, CHUNK)
    body = ",\n".join(f'        "{chunk}"' for chunk in chunks)
    TARGET.write_text(HEAD + body + TAIL, encoding="utf-8")
    print(f"{TARGET.relative_to(ROOT)}: {len(js)} байт JS → {len(chunks)} кусков")


if __name__ == "__main__":
    main()
