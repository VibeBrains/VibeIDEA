#!/usr/bin/env bash
# Гейт дистрибутива: проверяет СОБРАННЫЙ образ, а не исходники.
#
# Зачем отдельный скрипт: за один день 31.08.2026 четыре дефекта подряд нашлись только установкой —
# библиотека не доехала в дистрибутив, висячие симлинки уронили сборку, готовый плагин не грузился
# мимо индекса, встроенный сервер искался в пользовательской папке. Ни один юнит-тест не мог их
# увидеть: тесты исполняются там, где всё уже на classpath и все пути совпадают.
#
# Использование:
#   ./vibe-plugins/tools/checkVibeDist.sh [путь к .dmg, .tar.gz, .win.zip, .app или к распакованной папке]
# Без аргумента берётся свежий образ из out/vibeidea/artifacts — dmg на macOS, tar.gz на Linux,
# win.zip на Windows. Инсталлятор .exe здесь не открывается; его проверка — тихая установка и
# сравнение с распакованным zip, порядок в manuals/release.md (сборка своё сравнение exe и zip в
# dev-режиме пропускает).
#
# На Windows этот же скрипт работает из Git Bash (проверено 02.09.2026 на живой сборке); для чистого
# PowerShell есть близнец checkVibeDist.ps1 — проверки те же, и главная из них та же: серверы и
# адаптеры не просто лежат, а ЗАПУСКАЮТСЯ.
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
  TARGET=$(ls -t out/vibeidea/artifacts/*.dmg out/vibeidea/artifacts/*.tar.gz out/vibeidea/artifacts/*.win.zip 2>/dev/null | head -1 || true)
  [ -n "$TARGET" ] || { echo "✖ нет собранного образа в out/vibeidea/artifacts — сначала соберите инсталлятор"; exit 1; }
fi

# ROOT_DIR — каталог, в котором лежат plugins/ и license/: у приложения на macOS всё внутри
# Contents/, у Linux- и Windows-сборки — прямо в корне распакованной папки. Дальше проверки общие,
# и это не экономия строк: две копии одних и тех же проверок разошлись бы на первой же правке, а
# разошлись бы молча — обе зелёные, проверяют разное.
case "$TARGET" in
  *.dmg)
    MOUNTED=$(mktemp -d)
    hdiutil attach "$TARGET" -nobrowse -readonly -mountpoint "$MOUNTED" >/dev/null
    ROOT_DIR="$MOUNTED/VibeIDEA.app/Contents"
    ;;
  *.tar.gz)
    UNPACKED=$(mktemp -d)
    tar -xzf "$TARGET" -C "$UNPACKED"
    # Архив разворачивается в один каталог с версией в имени — берём его, каким бы он ни был.
    ROOT_DIR=$(find "$UNPACKED" -maxdepth 1 -mindepth 1 -type d | head -1)
    ;;
  *.zip)
    # Windows-архив разворачивается сразу в корень: bin/, plugins/, jbr/ без общего каталога.
    UNPACKED=$(mktemp -d)
    unzip -q "$TARGET" -d "$UNPACKED"
    ROOT_DIR="$UNPACKED"
    ;;
  *.app) ROOT_DIR="$TARGET/Contents" ;;
  *)
    # Папка: либо распакованный .app, либо корень Linux/Windows-сборки.
    if [ -d "$TARGET/VibeIDEA.app/Contents" ]; then ROOT_DIR="$TARGET/VibeIDEA.app/Contents"
    elif [ -d "$TARGET/Contents" ]; then ROOT_DIR="$TARGET/Contents"
    else ROOT_DIR="$TARGET"
    fi
    ;;
esac

[ -n "${ROOT_DIR:-}" ] && [ -d "$ROOT_DIR/plugins" ] || {
  echo "✖ в $TARGET не найден каталог plugins — это не наша сборка или архив пуст"
  exit 1
}
APP_PLUGINS="$ROOT_DIR/plugins"
echo "  проверяю $TARGET"
fail=0
say() { printf '%s\n' "$1"; }

# --- 1. Наши плагины на месте ---
for plugin in vibe-agent vibe-lsp vibe-server vibe-theme vibe-http vibe-db; do
  [ -d "$APP_PLUGINS/$plugin" ] || { say "✖ нет плагина $plugin"; fail=1; }
done

# --- 2. Готовые плагины ВПИСАНЫ В ИНДЕКС, а не просто скопированы ---
# Каталог в plugins/ ничего не значит: платформа грузит встроенные плагины только по
# plugin-classpath.txt (разбор — knowledge/build/bundledPluginIndex.md).
INDEX="$APP_PLUGINS/plugin-classpath.txt"
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
if ! ls "$APP_PLUGINS/vibe-agent/lib/zxing-core.jar" >/dev/null 2>&1; then
  say "✖ нет zxing-core.jar в vibe-agent/lib — QR-код адреса превью не заработает."
  say "  Зависимость в BUILD.bazel на упаковку не влияет: её решает раскладка плагина."
  fail=1
fi

# --- 4. Языковые серверы в комплекте ---
SERVERS="$APP_PLUGINS/vibe-lsp/servers"
# Задачи и трекеры как в PhpStorm: платформа даёт только ядро Tasks & Contexts, а Open Task и трекеры
# приезжают плагином intellij.tasks.core — его отсутствие снаружи выглядит как «в IDE нет задач».
[ -d "$APP_PLUGINS/tasks" ] || { say "✖ нет плагина задач (plugins/tasks) — Open Task и трекеры не появятся"; fail=1; }
# Плагины PhpStorm-паритета, которые лежали в дереве невключёнными: .env, XPath/XSLT, JSONPath.
# Имена каталогов — из МОДУЛЯ, а не из папки исходников: intellij.dotenv едет в plugins/dotenv,
# а не в plugins/env-files-support. Проверено сборкой 03.09.2026 (гейт на этом и поймал ошибку).
for p in dotenv xpath jsonpath; do
  [ -d "$APP_PLUGINS/$p" ] || { say "✖ нет плагина $p — он есть в дереве, но не попал в дистрибутив"; fail=1; }
done
[ -f "$SERVERS/phpactor.phar" ] || { say "✖ нет встроенного phpactor.phar"; fail=1; }
[ -f "$SERVERS/phpactor-LICENSE" ] || { say "✖ нет текста лицензии рядом с phar (MIT требует)"; fail=1; }
# Windows-выключатель проверки Box едет рядом с phar: без него на Windows сервер не стартует.
[ -f "$SERVERS/phpactorNoPosixCheck.php" ] || { say "✖ нет phpactorNoPosixCheck.php рядом с phar — на Windows Phpactor не запустится"; fail=1; }
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
#
# Один запрос по stdio, ответ ждём ДО появления признака, а не фиксированную паузу: с паузой stdin
# закрывался раньше, чем сервер успевал стартовать под нагрузкой, и гейт обвинял сборку в том, чего
# в ней нет (плавал на Windows 02.09.2026: три прогона зелёные, один красный на той же сборке).
# stderr — в тот же поток, не в /dev/null: node на Windows с отброшенным stderr завершался, не
# записав ответ в stdout. Лишние строки проверке по подстроке не мешают.
SERVER_WAIT=20
server_answers() {  # <сообщение> <признак ответа> <команда…> → 0, если признак появился
  local msg="$1" marker="$2"; shift 2
  local out; out=$(mktemp)
  { printf 'Content-Length: %d\r\n\r\n%s' ${#msg} "$msg"; sleep "$SERVER_WAIT"; } | "$@" >"$out" 2>&1 &
  local pid=$! tick=0 seen=1
  while [ "$tick" -lt $((SERVER_WAIT * 2)) ]; do
    grep -a -q "$marker" "$out" 2>/dev/null && { seen=0; break; }
    sleep 0.5; tick=$((tick + 1))
  done
  # wait возвращает код убитого процесса (143), а под set -e это уронило бы сам гейт.
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null || true
  rm -f "$out"
  return "$seen"
}

CSS_ENTRY="$SERVERS/node/node_modules/vscode-langservers-extracted/bin/vscode-css-language-server"
if [ ! -f "$CSS_ENTRY" ]; then
  : # об отсутствии сервера уже сказано выше
elif ! command -v node >/dev/null 2>&1; then
  say "  node не найден — запуск встроенных серверов не проверялся"
else
  B='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
  server_answers "$B" definitionProvider node "$CSS_ENTRY" --stdio \
    || { say "✖ встроенный CSS-сервер не ответил на initialize"; fail=1; }
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
  # Эмуляция Windows на этой машине: все posix_* выключены, а проверка Box снята нашим prepend —
  # так проверяется и то, что выключатель работает, и то, что внутри phar нет незащищённого вызова.
  if [ -f "$SERVERS/phpactorNoPosixCheck.php" ]; then
    posix_fns=$(php -r 'echo implode(",", array_filter(get_defined_functions()["internal"], fn($f)=>str_starts_with($f,"posix_")));' 2>/dev/null || true)
    win_out=$(php -d "disable_functions=$posix_fns" -d "auto_prepend_file=$SERVERS/phpactorNoPosixCheck.php" "$SERVERS/phpactor.phar" --version 2>&1 || true)
    case "$win_out" in
      Phpactor*) say "  phpactor.phar стартует без функций posix и с выключенной проверкой Box (эмуляция Windows)" ;;
      *) say "✖ phpactor.phar без posix не стартует: $(printf '%s' "$win_out" | head -2 | tr '\n' ' ')"; fail=1 ;;
    esac
  fi
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
    # печатает адрес, на котором слушает, каким бы он ни был. Ждём признак, а не паузу — по той же
    # причине, что и у серверов выше.
    js_out=$(mktemp)
    node "$JS_DAP" 0 127.0.0.1 >"$js_out" 2>&1 &
    js_pid=$!; tick=0; js_ok=1
    while [ "$tick" -lt $((SERVER_WAIT * 2)) ]; do
      grep -a -q "Debug server listening at" "$js_out" 2>/dev/null && { js_ok=0; break; }
      sleep 0.5; tick=$((tick + 1))
    done
    kill "$js_pid" 2>/dev/null; wait "$js_pid" 2>/dev/null || true; rm -f "$js_out"
    [ "$js_ok" -eq 0 ] || { say "✖ встроенный адаптер vscode-js-debug не поднял сервер отладки"; fail=1; }
  fi
  if [ -f "$PHP_DAP" ]; then
    # pathFormat обязателен: без него адаптер отвечает отказом «only supports native paths», и
    # проверка «ответил ли» приняла бы отказ за успех.
    B='{"seq":1,"type":"request","command":"initialize","arguments":{"adapterID":"php","clientID":"vibe","pathFormat":"path"}}'
    server_answers "$B" '"success":true' node "$PHP_DAP" \
      || { say "✖ встроенный адаптер vscode-php-debug не ответил успехом на initialize"; fail=1; }
  fi
fi

# --- 6. Лицензии поставляемых серверов названы, и версии не разъехались ---
# Отчёт о третьих лицах генерируется из ЗАВИСИМОСТЕЙ модулей: phar и npm-дерево ему не видны, их
# приходится объявлять руками — а значит версия объявленного однажды разойдётся с закреплённой.
REPORT=$(ls "$ROOT_DIR/license/third-party-libraries.json" 2>/dev/null | head -1 || true)
if [ -z "$REPORT" ]; then
  say "✖ в дистрибутиве нет отчёта о третьих лицах (license/third-party-libraries.json)"
  fail=1
else
  PHPACTOR_PIN=$(grep -m1 '^PHPACTOR_V=' vibe-plugins/deps/pins.env | cut -d= -f2)
  grep -q "\"$PHPACTOR_PIN\"" "$REPORT" || {
    say "✖ версия Phpactor в отчёте о лицензиях не совпадает с закреплённой ($PHPACTOR_PIN)"
    fail=1
  }
  for var in JS_DEBUG_V PHP_DEBUG_V; do
    PIN=$(grep -m1 "^$var=" vibe-plugins/deps/pins.env | cut -d= -f2)
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
