#!/usr/bin/env bash
# Гейт дистрибутива: проверяет СОБРАННЫЙ образ, а не исходники.
#
# Зачем отдельный скрипт: за один день 31.08.2026 четыре дефекта подряд нашлись только установкой —
# библиотека не доехала в дистрибутив, висячие симлинки уронили сборку, готовый плагин не грузился
# мимо индекса, встроенный сервер искался в пользовательской папке. Ни один юнит-тест не мог их
# увидеть: тесты исполняются там, где всё уже на classpath и все пути совпадают.
#
# Использование:
#   ./vibe-plugins/tools/checkVibeDist.sh [путь к .dmg, .win.zip, .app или распакованному каталогу]
# Без аргумента берётся свежий образ из out/vibeidea/artifacts: dmg на macOS-сборке, win.zip на
# Windows-сборке. Инсталлятор .exe здесь не открывается (7z на машине нет); его проверка — тихая
# установка во временный каталог и сравнение с распакованным zip, порядок в manuals/release.md.
# Сборка на это не полагается: своё сравнение exe и zip она в dev-режиме пропускает.
set -euo pipefail
cd "$(dirname "$0")/../.."
. vibe-plugins/tools/pythonBin.sh

TARGET="${1:-}"
MOUNTED=""
UNPACKED=""
cleanup() {
  [ -n "$MOUNTED" ] && hdiutil detach "$MOUNTED" >/dev/null 2>&1 || true
  [ -n "$UNPACKED" ] && rm -rf "$UNPACKED" || true
}
trap cleanup EXIT

if [ -z "$TARGET" ]; then
  TARGET=$(ls -t out/vibeidea/artifacts/*.dmg out/vibeidea/artifacts/*.win.zip 2>/dev/null | head -1 || true)
  [ -n "$TARGET" ] || { echo "✖ нет собранного образа (dmg или win.zip) в out/vibeidea/artifacts — сначала соберите инсталлятор"; exit 1; }
fi

# DIST — каталог, в котором лежат plugins/ и license/. На macOS это Contents/ внутри бандла, на
# Windows — корень архива: раскладка одна, различается только обёртка вокруг неё.
case "$TARGET" in
  *.dmg)
    MOUNTED=$(mktemp -d)
    hdiutil attach "$TARGET" -nobrowse -readonly -mountpoint "$MOUNTED" >/dev/null
    DIST="$MOUNTED/VibeIDEA.app/Contents"
    ;;
  *.zip)
    UNPACKED=$(mktemp -d)
    unzip -q "$TARGET" -d "$UNPACKED"
    DIST="$UNPACKED"
    ;;
  *.app) DIST="$TARGET/Contents" ;;
  *)
    if [ -d "$TARGET/VibeIDEA.app" ]; then DIST="$TARGET/VibeIDEA.app/Contents"
    elif [ -d "$TARGET/Contents" ]; then DIST="$TARGET/Contents"
    else DIST="$TARGET"
    fi
    ;;
esac

[ -d "$DIST/plugins" ] || { echo "✖ не найден дистрибутив (нет каталога plugins/): $DIST"; exit 1; }
echo "  проверяю $TARGET"
fail=0
say() { printf '%s\n' "$1"; }

# --- 1. Наши плагины на месте ---
for plugin in vibe-agent vibe-lsp vibe-server vibe-theme; do
  [ -d "$DIST/plugins/$plugin" ] || { say "✖ нет плагина $plugin"; fail=1; }
done

# --- 2. Готовые плагины ВПИСАНЫ В ИНДЕКС, а не просто скопированы ---
# Каталог в plugins/ ничего не значит: платформа грузит встроенные плагины только по
# plugin-classpath.txt (разбор — knowledge/build/bundledPluginIndex.md).
INDEX="$DIST/plugins/plugin-classpath.txt"
[ -f "$INDEX" ] || { say "✖ нет индекса встроенных плагинов $INDEX"; fail=1; }
if [ -f "$INDEX" ]; then
  # Индекс двоичный: grep -a читает его как текст, -o считает вхождения, а не строки (`strings`
  # на Windows нет).
  hits=$(grep -a -o -i "lsp4ij" "$INDEX" | wc -l | tr -d ' ')
  # Двух-трёх упоминаний недостаточно: столько даёт само имя каталога в путях.
  if [ "$hits" -lt 20 ]; then
    say "✖ LSP4IJ не вписан в индекс встроенных плагинов (упоминаний: $hits) — плагин не загрузится,"
    say "  и вместе с ним молча исчезнут TypeScript, PHP, CSS и ESLint. Поставка готового плагина —"
    say "  только через ProductProperties.getAdditionalPluginPaths()."
    fail=1
  fi
fi

# --- 3. Библиотеки, которые едут внутри наших плагинов ---
if ! ls "$DIST/plugins/vibe-agent/lib/zxing-core.jar" >/dev/null 2>&1; then
  say "✖ нет zxing-core.jar в vibe-agent/lib — QR-код адреса превью не заработает."
  say "  Зависимость в BUILD.bazel на упаковку не влияет: её решает раскладка плагина."
  fail=1
fi

# --- 4. Языковые серверы в комплекте ---
SERVERS="$DIST/plugins/vibe-lsp/servers"
[ -f "$SERVERS/phpactor.phar" ] || { say "✖ нет встроенного phpactor.phar"; fail=1; }
[ -f "$SERVERS/phpactor-LICENSE" ] || { say "✖ нет текста лицензии рядом с phar (MIT требует)"; fail=1; }
for entry in \
  "node/node_modules/@vtsls/language-server/bin/vtsls.js" \
  "node/node_modules/vscode-langservers-extracted/bin/vscode-css-language-server" \
  "node/node_modules/vscode-langservers-extracted/bin/vscode-eslint-language-server"; do
  [ -f "$SERVERS/$entry" ] || { say "✖ нет встроенного сервера: $entry"; fail=1; }
done

# Висячие ссылки роняют сборку дистрибутива и бесполезны сами по себе.
dangling=$(find "$SERVERS" -type l ! -exec test -e {} \; -print 2>/dev/null | head -3 || true)
[ -z "$dangling" ] || { say "✖ висячие симлинки в наборе серверов:"; say "$dangling"; fail=1; }

# --- 5. Серверы РЕАЛЬНО СТАРТУЮТ из образа, а не просто лежат ---
# Файл на месте — это ещё не работающий сервер: проверяем ответом на настоящий LSP-запрос.
CSS_ENTRY="$SERVERS/node/node_modules/vscode-langservers-extracted/bin/vscode-css-language-server"
if [ ! -f "$CSS_ENTRY" ]; then
  : # об отсутствии сервера уже сказано выше
elif ! command -v node >/dev/null 2>&1; then
  say "  node не найден — запуск встроенных серверов не проверялся"
else
  B='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
  # Первые 2000 байт: ответ начинается с заголовка и длинного списка возможностей, и признак
  # `definitionProvider` стоит за пределами первых 200 (на Windows-сборке гейт валился именно на
  # этом, сервер при этом отвечал исправно).
  # stderr — в тот же поток, не в /dev/null: node на Windows с отброшенным stderr завершался,
  # не записав ответ в stdout (воспроизведено четырьмя прогонами 02.09.2026: с 2>&1 ответ есть,
  # с 2>/dev/null — пусто). Лишние строки stderr проверке по подстроке не мешают.
  answer=$({ printf 'Content-Length: %d\r\n\r\n%s' ${#B} "$B"; sleep 5; } \
    | node "$CSS_ENTRY" --stdio 2>&1 | head -c 2000 || true)
  case "$answer" in
    *definitionProvider*) : ;;
    *) say "✖ встроенный CSS-сервер не ответил на initialize"; fail=1 ;;
  esac
fi

# Два условия разведены: «нет phar» и «нет php» — разные новости, и общее сообщение об одном из
# них врёт про другое (поймано первым же прогоном по подложенному дефекту).
if [ ! -f "$SERVERS/phpactor.phar" ]; then
  : # об отсутствии phar уже сказано выше
elif ! command -v php >/dev/null 2>&1; then
  say "  php не найден — запуск встроенного Phpactor не проверялся"
else
  phpactor_out=$(php "$SERVERS/phpactor.phar" --version 2>&1 || true)
  case "$phpactor_out" in
    *"Phpactor"*) : ;;
    *'requires the extension "posix"'*)
      # Phar собран Box с проверкой требований, и среди них ext-posix — расширение, которого в
      # Windows-сборках PHP не существует. Это не дефект дистрибутива, а известное ограничение
      # Phpactor на Windows (проверено 02.09.2026: обход проверки не помогает); оно названо в
      # заметках релиза. Сломанный phar сюда не попадёт — он не печатает текст проверки требований.
      say "  Phpactor на этой машине не запускается: phar требует ext-posix (нет на Windows) — известное ограничение, не дефект сборки" ;;
    *) say "✖ встроенный phpactor.phar не запускается"; fail=1 ;;
  esac
fi

# --- 5б. Отладочные адаптеры: файлы на месте И запускаются ---
#
# Отладчик — то, что проверяют один раз: если точка останова не сработала, человек возвращается к
# var_dump и больше не пробует. Поэтому оба адаптера здесь именно ЗАПУСКАЮТСЯ.
JS_DAP="$SERVERS/dap/vibeJsDebug/js-debug/src/dapDebugServer.js"
PHP_DAP="$SERVERS/dap/vibePhpDebug/extension/out/phpDebug.js"
[ -f "$JS_DAP" ] || { say "✖ нет встроенного адаптера vscode-js-debug"; fail=1; }
[ -f "$PHP_DAP" ] || { say "✖ нет встроенного адаптера vscode-php-debug"; fail=1; }
# MIT: текст лицензии обязан ехать рядом с копией.
[ -f "$SERVERS/dap/vibeJsDebug/js-debug/LICENSE" ] || { say "✖ нет лицензии vscode-js-debug"; fail=1; }
[ -f "$SERVERS/dap/vibePhpDebug/extension/LICENSE.txt" ] || { say "✖ нет лицензии vscode-php-debug"; fail=1; }

if ! command -v node >/dev/null 2>&1; then
  say "  node не найден — запуск встроенных адаптеров не проверялся"
else
  if [ -f "$JS_DAP" ]; then
    # Порт 0 — ОС выбирает свободный. Зашитый номер выглядел безобиднее и валил ГЕЙТ, а не сборку:
    # процесс от прошлого прогона оставался слушать, второй запуск не мог занять порт, и проверка
    # обвиняла дистрибутив в том, чего в нём нет. Признак готовности от этого не страдает — адаптер
    # печатает адрес, на котором слушает, каким бы он ни был.
    out=$( { node "$JS_DAP" 0 127.0.0.1 & pid=$!; sleep 4; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; } 2>&1 | head -c 200 || true)
    case "$out" in
      *"Debug server listening at"*) : ;;
      *) say "✖ встроенный адаптер vscode-js-debug не поднял сервер отладки"; fail=1 ;;
    esac
  fi
  if [ -f "$PHP_DAP" ]; then
    # pathFormat обязателен: без него адаптер отвечает отказом «only supports native paths», и
    # проверка «ответил ли» приняла бы отказ за успех.
    B='{"seq":1,"type":"request","command":"initialize","arguments":{"adapterID":"php","clientID":"vibe","pathFormat":"path"}}'
    answer=$({ printf 'Content-Length: %d\r\n\r\n%s' ${#B} "$B"; sleep 5; } \
      | node "$PHP_DAP" 2>&1 | head -c 2000 || true)
    case "$answer" in
      *'"success":true'*) : ;;
      *) say "✖ встроенный адаптер vscode-php-debug не ответил успехом на initialize"; fail=1 ;;
    esac
  fi
fi

# --- 6. Лицензии поставляемых серверов названы, и версии не разъехались ---
# Отчёт о третьих лицах генерируется из ЗАВИСИМОСТЕЙ модулей: phar и npm-дерево ему не видны, их
# приходится объявлять руками — а значит версия объявленного однажды разойдётся с закреплённой.
REPORT=$(ls "$DIST/license/third-party-libraries.json" 2>/dev/null | head -1 || true)
if [ -z "$REPORT" ]; then
  say "✖ в дистрибутиве нет отчёта о третьих лицах (license/third-party-libraries.json)"
  fail=1
else
  PHPACTOR_PIN=$(grep -m1 '^PHPACTOR_V=' vibe-plugins/deps/download.sh | cut -d= -f2)
  grep -q "\"$PHPACTOR_PIN\"" "$REPORT" || {
    say "✖ версия Phpactor в отчёте о лицензиях не совпадает с закреплённой ($PHPACTOR_PIN)"
    fail=1
  }
  for var in JS_DEBUG_V PHP_DEBUG_V; do
    PIN=$(grep -m1 "^$var=" vibe-plugins/deps/download.sh | cut -d= -f2)
    grep -q "\"$PIN\"" "$REPORT" || { say "✖ версия отладчика ($var=$PIN) в отчёте о лицензиях не совпадает"; fail=1; }
  done
  for pkg in "@vtsls/language-server" "vscode-langservers-extracted"; do
    PIN=$("$PYTHON" -c "import json;print(json.load(open('vibe-plugins/deps/servers-npm/package.json'))['dependencies']['$pkg'])")
    grep -q "\"$PIN\"" "$REPORT" || { say "✖ версия $pkg в отчёте о лицензиях не совпадает с закреплённой ($PIN)"; fail=1; }
  done
fi

if [ "$fail" -ne 0 ]; then
  say "Гейт дистрибутива: ПРОВАЛЕН"
  exit 1
fi
say "Гейт дистрибутива: плагины в индексе, библиотеки и серверы на месте и запускаются"
