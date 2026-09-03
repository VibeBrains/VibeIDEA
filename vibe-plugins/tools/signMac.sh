#!/usr/bin/env bash
# Подпись и нотаризация macOS-сборки — ПОСЛЕ сборки, отдельным шагом.
#
# Почему не внутри сборки: платформенный путь подписи (SignTool) рассчитан на инфраструктуру
# вендора — отдельную машину подписи с её протоколом и учётками. Для одного человека с одним Mac
# честный путь — стандартные инструменты Apple поверх готового образа: codesign → dmg → notarytool →
# stapler. Скрипт делает ровно это и ничего не выдумывает, когда учётки нет: без Developer ID он
# останавливается первой же строкой и говорит, что купить и куда положить.
#
# Что нужно от владельца (один раз):
#   1. Apple Developer Program (учётка организации или личная), сертификат «Developer ID Application».
#   2. Сертификат в связке ключей этой машины: `security find-identity -v -p codesigning` его показывает.
#   3. Профиль нотаризации: `xcrun notarytool store-credentials vibeidea --apple-id … --team-id … --password <app-specific>`.
#   4. Переменные окружения: VIBE_MAC_SIGN_IDENTITY="Developer ID Application: Имя (TEAMID)", VIBE_NOTARY_PROFILE=vibeidea.
#
# Использование: ./vibe-plugins/tools/signMac.sh [--dry-run] [путь к .dmg]
#   --dry-run — проверить инструменты и учётку и перечислить, что было бы подписано, ничего не трогая.
set -euo pipefail
cd "$(dirname "$0")/../.."

DRY=false
DMG=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=true ;;
    *) DMG="$arg" ;;
  esac
done
ARTIFACTS=out/vibeidea/artifacts
[ -n "$DMG" ] || DMG=$(ls -t "$ARTIFACTS"/*.dmg 2>/dev/null | head -1 || true)

say() { printf '%s\n' "$1"; }
need() { command -v "$1" >/dev/null 2>&1 || { say "✖ нет инструмента $1 — поставьте Xcode Command Line Tools: xcode-select --install"; exit 1; }; }

say "== подпись macOS: проверка инструментов"
need codesign; need hdiutil; need xcrun
xcrun --find notarytool >/dev/null 2>&1 || { say "✖ notarytool не найден — нужен Xcode 13+ или свежие Command Line Tools"; exit 1; }
xcrun --find stapler >/dev/null 2>&1 || { say "✖ stapler не найден — та же поставка, что notarytool"; exit 1; }

IDENTITY="${VIBE_MAC_SIGN_IDENTITY:-}"
PROFILE="${VIBE_NOTARY_PROFILE:-}"
if [ -z "$IDENTITY" ]; then
  say "✖ не задана VIBE_MAC_SIGN_IDENTITY — сертификата «Developer ID Application» у сборки нет."
  say "  Нужна учётка Apple Developer Program и сертификат в связке ключей; проверить: security find-identity -v -p codesigning"
  say "  Пока его нет, образ выходит неподписанным, и заметки релиза обязаны говорить это прямо."
  exit 2
fi
security find-identity -v -p codesigning | grep -q "$IDENTITY" \
  || { say "✖ в связке ключей нет сертификата «$IDENTITY» — сертификат куплен, но не установлен на эту машину"; exit 2; }
[ -n "$PROFILE" ] || { say "✖ не задан VIBE_NOTARY_PROFILE — профиль notarytool store-credentials; без нотаризации Gatekeeper всё равно откажет"; exit 2; }
[ -n "$DMG" ] && [ -f "$DMG" ] || { say "✖ нет образа .dmg в $ARTIFACTS — сначала соберите инсталлятор"; exit 1; }

# Подписывается РАСПАКОВАННОЕ приложение, а не dmg целиком: подпись dmg не подписывает содержимое,
# и Gatekeeper проверяет именно .app. Поэтому образ пересобирается после подписи.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
say "== образ: $DMG"
if $DRY; then
  say "  [dry-run] смонтировал бы образ, подписал бы VibeIDEA.app целиком (--deep --options runtime --timestamp),"
  say "  [dry-run] пересобрал бы dmg, подписал бы его, отправил бы на нотаризацию профилем «$PROFILE» и пришил бы билет."
  say "  [dry-run] идентичность: $IDENTITY"
  exit 0
fi

MOUNT=$(hdiutil attach -nobrowse -readonly "$DMG" | awk -F'\t' '/\/Volumes\//{print $NF}' | tail -1)
[ -n "$MOUNT" ] || { say "✖ не удалось смонтировать $DMG"; exit 1; }
APP=$(ls -d "$MOUNT"/*.app | head -1)
cp -R "$APP" "$WORK/"
hdiutil detach "$MOUNT" -quiet
APP="$WORK/$(basename "$APP")"

say "== codesign: $(basename "$APP")"
# --deep подписывает вложенные бинари (JBR, нативные библиотеки, лаунчер); runtime — hardened
# runtime, без него нотаризация отказывает; timestamp — подпись живёт после истечения сертификата.
codesign --force --deep --options runtime --timestamp --sign "$IDENTITY" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

SIGNED="${DMG%.dmg}-signed.dmg"
say "== dmg: $SIGNED"
rm -f "$SIGNED"
hdiutil create -volname "$(basename "$APP" .app)" -srcfolder "$APP" -ov -format UDZO "$SIGNED" -quiet
codesign --force --timestamp --sign "$IDENTITY" "$SIGNED"

say "== нотаризация (ждём ответа Apple, обычно минуты)"
xcrun notarytool submit "$SIGNED" --keychain-profile "$PROFILE" --wait
xcrun stapler staple "$SIGNED"
spctl --assess --type open --context context:primary-signature -v "$SIGNED" || {
  say "✖ Gatekeeper не принял образ после нотаризации — читать журнал: xcrun notarytool log <id> --keychain-profile $PROFILE"; exit 1; }
say "✔ подписано и нотаризовано: $SIGNED"
say "  Публиковать именно этот файл; неподписанный оригинал оставлен рядом для сравнения хешей."
