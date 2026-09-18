#!/usr/bin/env bash
# Самоподписанная подпись сборки под macOS — чтобы связка ключей не спрашивала пароль на каждой.
#
# Откуда берётся вопрос: ключи провайдеров лежат в связке ключей macOS, а доступ к записи связка
# разрешает КОНКРЕТНОМУ приложению — по его подписи. Неподписанная сборка каждый раз выглядит новым
# приложением, поэтому «Разрешать всегда» действует ровно до следующей сборки, и за день владелец
# вводил пароль по три-четыре раза (18.09.2026).
#
# Самоподписанный сертификат делает подпись ПОСТОЯННОЙ: все наши сборки подписаны одним и тем же
# удостоверением, и разрешение, выданное однажды, продолжает действовать. К нотаризации Apple это
# отношения не имеет — она отдельная задача и стоит денег; здесь речь только о своей машине.
#
# Две команды:
#   ./vibe-plugins/tools/macSelfSign.sh identity          — создать удостоверение (один раз, спросит пароль)
#   ./vibe-plugins/tools/macSelfSign.sh sign <файл.dmg>   — подписать приложение внутри образа
set -euo pipefail
cd "$(dirname "$0")/../.."

IDENTITY="VibeIDEA Self-Signed"
KEY_DIR="$HOME/.vibeidea-signing"

say() { printf '%s\n' "$1"; }

have_identity() {
  security find-identity -v -p codesigning 2>/dev/null | grep -q "$IDENTITY"
}

create_identity() {
  if have_identity; then
    say "Удостоверение «$IDENTITY» уже есть — создавать нечего."
    return 0
  fi
  mkdir -p "$KEY_DIR"
  chmod 700 "$KEY_DIR"
  local key="$KEY_DIR/vibeidea.key.pem"
  local cert="$KEY_DIR/vibeidea.cert.pem"
  local bundle="$KEY_DIR/vibeidea.p12"
  # Назначение сертификата обязано быть именно codeSigning: без этого расширения codesign
  # удостоверение не видит, а `security find-identity` показывает пустой список.
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$key" -out "$cert" \
    -subj "/CN=$IDENTITY/O=VibeBrains" \
    -addext "basicConstraints=critical,CA:false" \
    -addext "keyUsage=critical,digitalSignature" \
    -addext "extendedKeyUsage=critical,codeSigning" >/dev/null 2>&1
  openssl pkcs12 -export -out "$bundle" -inkey "$key" -in "$cert" -name "$IDENTITY" -passout pass:vibeidea >/dev/null
  chmod 600 "$key" "$cert" "$bundle"
  say "Сертификат создан: $cert"
  say "Дальше macOS спросит пароль от связки ключей — дважды: на импорт и на доверие."
  security import "$bundle" -k "$HOME/Library/Keychains/login.keychain-db" -P vibeidea -T /usr/bin/codesign
  # Доверие ставится в ПОЛЬЗОВАТЕЛЬСКОМ домене (-r trustRoot без -d): админские права не нужны,
  # и сертификат действует только для этого пользователя — ровно то, что нужно своей машине.
  security add-trusted-cert -r trustRoot -k "$HOME/Library/Keychains/login.keychain-db" "$cert"
  if have_identity; then
    say "✓ Удостоверение «$IDENTITY» готово. Теперь сборки подписываются им."
  else
    say "✖ Удостоверение не появилось в списке codesigning. Проверьте: security find-identity -v -p codesigning"
    exit 1
  fi
}

sign_dmg() {
  local dmg="${1:-}"
  [ -f "$dmg" ] || { say "✖ нет такого образа: $dmg"; exit 1; }
  have_identity || { say "✖ нет удостоверения «$IDENTITY» — сперва: $0 identity"; exit 1; }

  local work mount rw
  work="$(mktemp -d)"
  rw="$work/rw.dmg"
  mount="$work/mnt"
  mkdir -p "$mount"

  # Образ приходит сжатым и только для чтения: подписать приложение внутри можно, только сделав
  # его записываемым. Пересобирать образ с нуля нельзя — вместе с ним пропало бы оформленное окно
  # установки (фон, раскладка иконок), которое строит сборка.
  say "  распаковываю образ"
  hdiutil convert "$dmg" -format UDRW -o "$rw" -quiet
  say "  подключаю"
  hdiutil attach "$rw" -mountpoint "$mount" -nobrowse -quiet
  local app
  app="$(find "$mount" -maxdepth 1 -name '*.app' | head -1)"
  if [ -z "$app" ]; then
    hdiutil detach "$mount" -quiet || true
    rm -rf "$work"
    say "✖ в образе нет приложения"
    exit 1
  fi
  say "  подписываю $(basename "$app") — это займёт минуту"
  # --deep: внутри лежат сотни своих бинарей (JBR, языковые серверы, отладчики), и подпись только
  # внешней обёртки macOS не примет. --force: у части вложенного уже есть чужая подпись.
  codesign --force --deep --timestamp=none --sign "$IDENTITY" "$app"
  codesign --verify --deep --strict "$app" >/dev/null 2>&1 || say "  ⚠ проверка подписи с --strict не прошла (для своей машины не критично)"
  hdiutil detach "$mount" -quiet
  say "  сжимаю обратно"
  local signed="$work/signed.dmg"
  hdiutil convert "$rw" -format UDZO -o "$signed" -quiet
  mv "$signed" "$dmg"
  rm -rf "$work"
  say "✓ образ подписан удостоверением «$IDENTITY»: $dmg"
}

case "${1:-}" in
  identity) create_identity ;;
  sign) shift; sign_dmg "${1:-}" ;;
  *) say "использование: $0 identity | $0 sign <файл.dmg>"; exit 1 ;;
esac
