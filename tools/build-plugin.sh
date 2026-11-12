#!/usr/bin/env bash
#
# Собирает релизный файл плагина: .jar с classes.dex внутри, готовый для
# DexClassLoader, плюс sha256 и подпись Ed25519.
#
#   ./tools/build-plugin.sh            # собрать
#   ./tools/build-plugin.sh keygen     # создать пару ключей (один раз)
#
# Почему не просто ./gradlew jar: Gradle кладёт в jar файлы .class,
# а DexClassLoader понимает только .dex. Шаг d8 ниже как раз это исправляет.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="plugin-tiktok"
ENTRY_CLASS="dev.client.plugin.tiktok.TikTokProviderFactory"
DIST="$ROOT/dist"
KEY="$ROOT/tools/plugin-signing-key.pem"
R8_VERSION="8.5.35"
R8_JAR="$ROOT/tools/.cache/r8-$R8_VERSION.jar"
R8_URL="https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"

die() { echo "ошибка: $*" >&2; exit 1; }

# ---------------------------------------------------------------- keygen
if [[ "${1:-}" == "keygen" ]]; then
    if [[ -f "$KEY" ]]; then
        die "ключ уже есть: $KEY (не перезаписываю)"
    fi
    mkdir -p "$(dirname "$KEY")"
    openssl genpkey -algorithm ed25519 -out "$KEY"
    echo "приватный ключ: $KEY"
    echo "НЕ коммить его. Потеряешь — придётся менять ключ в клиенте релизом."
    echo
    echo "публичный ключ для TrustedKey.PUBLIC_KEY_BASE64:"
    openssl pkey -in "$KEY" -pubout -outform DER | base64 -w0
    echo
    exit 0
fi

# ---------------------------------------------------------------- сборка
command -v java >/dev/null || die "нет java (apk add openjdk17)"
command -v zip  >/dev/null || die "нет zip (apk add zip)"
command -v unzip >/dev/null || die "нет unzip"
[[ -f "$KEY" ]] || die "нет ключа подписи. Сначала: $0 keygen"

GRADLE="$ROOT/gradlew"
[[ -x "$GRADLE" ]] || GRADLE="gradle"
command -v "$GRADLE" >/dev/null || die "нет ни ./gradlew, ни gradle"

echo "==> 1/5 gradle :$MODULE:jar"
"$GRADLE" -p "$ROOT" ":$MODULE:jar" --console=plain -q

# -print -quit вместо | head -1: при pipefail закрытый pipe от head
# уронил бы find сигналом SIGPIPE, а с ним и весь скрипт.
PLAIN_JAR="$(find "$ROOT/$MODULE/build/libs" -name '*.jar' ! -name '*sources*' -print -quit 2>/dev/null)"
[[ -n "$PLAIN_JAR" ]] || die "gradle не выдал jar"

echo "==> 2/5 r8/d8"
mkdir -p "$(dirname "$R8_JAR")"
if [[ ! -f "$R8_JAR" ]]; then
    curl -fsSL "$R8_URL" -o "$R8_JAR" || die "не скачался r8"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex"
unzip -q "$PLAIN_JAR" -d "$WORK/classes"

CLASS_COUNT="$(find "$WORK/classes" -name '*.class' | wc -l)"
[[ "$CLASS_COUNT" -gt 0 ]] || die "в jar нет .class — проверь compileOnly/implementation"

find "$WORK/classes" -name '*.class' -print0 \
    | xargs -0 java -cp "$R8_JAR" com.android.tools.r8.D8 \
        --release --min-api 26 --no-desugaring --output "$WORK/dex" \
    || die "d8 упал"

[[ -f "$WORK/dex/classes.dex" ]] || die "d8 не создал classes.dex"

echo "==> 3/5 упаковка"
mkdir -p "$DIST"
OUT="$DIST/provider-tiktok.jar"
rm -f "$OUT"

# Ресурсы (если появятся) копируем рядом с classes.dex,
# иначе они не попадут в итоговый jar: d8 переносит только классы.
if [ -n "$(find "$WORK/classes" -type f ! -name '*.class' -print -quit)" ]; then
    ( cd "$WORK/classes" && find . -type f ! -name '*.class' | while IFS= read -r f; do
        mkdir -p "$WORK/dex/$(dirname "$f")"
        cp "$f" "$WORK/dex/$f"
    done )
fi

( cd "$WORK/dex" && zip -q -X -r "$OUT" . )

echo "==> 4/5 sha256"
HASH="$(sha256sum "$OUT" | cut -d' ' -f1)"
echo "    $HASH"

echo "==> 5/5 подпись"
# -rawin требует регулярный файл: из pipe OpenSSL 3 отвечает
# "unable to determine file size for oneshot operation".
printf '%s' "$HASH" > "$WORK/hash.txt"
openssl pkeyutl -sign -inkey "$KEY" -rawin -in "$WORK/hash.txt" -out "$WORK/sig.bin"
SIG="$(base64 -w0 < "$WORK/sig.bin")"

SIZE="$(du -h "$OUT" | cut -f1)"

VERSION="${PLUGIN_VERSION:-1}"
MIN_CLIENT="${PLUGIN_MIN_CLIENT:-1}"
DOWNLOAD_URL="${PLUGIN_DOWNLOAD_URL:-https://github.com/MrRed-dev6/driftfeed-plugin/releases/latest/download/provider-tiktok.jar}"

MANIFEST="$DIST/sources.json"
cat > "$MANIFEST" <<JSON
{
  "id": "tiktok",
  "displayName": "TikTok",
  "version": $VERSION,
  "contractMajor": 1,
  "minClientVersion": $MIN_CLIENT,
  "entryClass": "$ENTRY_CLASS",
  "downloadUrls": [
    "$DOWNLOAD_URL"
  ],
  "sha256": "$HASH",
  "signatureBase64": "$SIG"
}
JSON

echo
echo "готово: $OUT ($SIZE)"
echo "манифест: $MANIFEST"
echo
cat "$MANIFEST"
