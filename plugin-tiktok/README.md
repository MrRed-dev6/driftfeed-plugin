# plugin-tiktok

Настоящий источник данных: лента, поиск, видео, комментарии, профиль.
Реализует контракт `Provider` из `provider-api`, поэтому для клиента он
неотличим от тестового провайдера.

## Как устроено

```
TikTokProviderFactory   ← entryClass для sources.json
  └── TikTokProvider    ← Provider
        └── TikTokApi   ← URL → httpGet → JSON → Page
              ├── Signer        ← подпись запросов
              ├── TikTokSession ← msToken и tt_webid_v2
              └── TikTokParser  ← разбор ответов (веб и app формат)
```

**Подпись.** TikTok не отдаёт данные без параметров подписи в URL. Считать их
надо JavaScript'ом самого TikTok, поэтому плагин отдаёт скрипт в
`ProviderContext.evalJs(script, globals)` и получает обратно подписанный URL.
Движок (QuickJS) держит клиент — плагин остаётся чистым dex без `.so`.

Скрипт SDK лежит ресурсом в jar плагина, а не в клиенте: обновляется выпуском
новой версии плагина. Подробности — в `SIGN_SDK.md`.

**Настроек нет.** Регион, язык и user-agent — константы в `TikTokConfig`.
Клиент — чужая сторона, и новый метод конфигурации пришлось бы протаскивать
через общий контракт. Меняется выпуском новой версии плагина.

**Сессия.** `msToken` и `tt_webid_v2` генерируются в `TikTokSession`, а не
принимаются от сервера: `ProviderContext` отдаёт только тело ответа, `Set-Cookie`
плагину не виден. Веб-клиент TikTok поступает так же — считает `msToken` сам
и отправляет его и параметром URL, и кукой.

Сгенерированное значение сохраняется через `ProviderContext.setStorage`, чтобы
пережить перезапуск приложения: иначе каждый старт выглядит как новый
анонимный клиент. Читается один раз, перед первым запросом, с проверкой
формата. Если хранилища в клиенте нет, работает `NoStorage`, и сессия живёт
в памяти.

## Что нужно от клиента

1. `ProviderContext` с сетью и `evalJs` — реализация на стороне клиента
   (`ClientProviderContext`).
2. `DexClassLoader` с `parent = classLoader приложения`, иначе классы
   `dev.client.provider.*` не найдутся.

## Сборка

```bash
bash tools/build-plugin.sh keygen   # один раз, создаёт Ed25519-ключ
bash tools/build-plugin.sh          # dist/provider-tiktok.jar + sources.json
```

Скрипт прогоняет классы через `d8`, потому что `DexClassLoader` грузит только
`classes.dex`, а не `.class`. Ресурсы (JS-скрипт подписи) переносятся
в итоговый jar отдельно.

## Тесты

```bash
gradle :plugin-tiktok:test
```

Проверяют разбор обоих форматов ответа (веб и app) и пагинацию.

## Сборка на телефоне не работает

JVM под proot не запускается: Android запрещает исполняемые страницы памяти,
`Failed to mark memory page as executable`. Собирать — только на компьютере
или в GitHub Actions.
