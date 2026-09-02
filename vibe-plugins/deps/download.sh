#!/bin/sh
# Download pinned external artifacts for the VibeIDEA distribution.
#
# LSP4IJ (EPL-2.0) is bundled from its GitHub release, not from JetBrains Marketplace.
# Phpactor (MIT) is bundled as its single phar: 4.3 MB for a whole language working out of the box.
# Both are PINNED by version and verified by sha256 — «latest» in a build script is a build that
# means something different every day.
set -e
cd "$(dirname "$0")"
# Версии — из pins.env, одного файла на оба скрипта: копия версии расходится не «если», а «когда»,
# и расходится молча — обе сборки успешны, содержимое разное.
. ./pins.env
V=$LSP4IJ_V
SHA=$LSP4IJ_SHA
[ -f "lsp4ij-$V.zip" ] || curl -sL -o "lsp4ij-$V.zip" "https://github.com/redhat-developer/lsp4ij/releases/download/$V/lsp4ij-$V.zip"
echo "$SHA  lsp4ij-$V.zip" | shasum -a 256 -c -
rm -rf extracted && mkdir -p extracted && unzip -q "lsp4ij-$V.zip" -d extracted

# --- Phpactor: PHP language server, one self-contained phar ---
# Runs on the machine's PHP (>= 8.1). We do not ship a PHP runtime: a PHP developer without PHP
# does not exist, and bundling one would add a second interpreter to keep patched.
[ -f "phpactor-$PHPACTOR_V.phar" ] || curl -sL -o "phpactor-$PHPACTOR_V.phar" \
  "https://github.com/phpactor/phpactor/releases/download/$PHPACTOR_V/phpactor.phar"
echo "$PHPACTOR_SHA  phpactor-$PHPACTOR_V.phar" | shasum -a 256 -c -
mkdir -p extracted/servers && cp "phpactor-$PHPACTOR_V.phar" extracted/servers/phpactor.phar
# MIT requires the licence text to travel with the copy — it ships next to the phar.
[ -f "phpactor-LICENSE" ] || curl -sL -o "phpactor-LICENSE" \
  "https://raw.githubusercontent.com/phpactor/phpactor/$PHPACTOR_V/LICENSE"
cp "phpactor-LICENSE" extracted/servers/phpactor-LICENSE
printf 'Phpactor %s (MIT), bundled from the project release; see phpactor-LICENSE.\n' "$PHPACTOR_V" \
  > extracted/servers/README.txt

# --- Языковые серверы на Node: vtsls (TS/JS), CSS и ESLint ---
#
# `npm ci` по закреплённому package-lock.json, а не `npm install`: lock несёт integrity-хеши
# каждого пакета, то есть тот же уровень доверия, что sha256 у phar. Node в дистрибутив НЕ кладём —
# запускаемся на машинном: фронтендер без Node так же редок, как PHP-разработчик без PHP, а второй
# рантайм пришлось бы патчить при каждой уязвимости.
(
  cd servers-npm
  npm ci --omit=dev --no-audit --no-fund
  # Вложенная копия TypeScript внутри vscode-langservers-extracted — 64 МБ дубликата: она нужна
  # серверам html/markdown, которых мы не подключаем (платформа обслуживает их сама). Проверено:
  # css, eslint и vtsls стартуют без неё. Верхнеуровневый TypeScript остаётся — на нём работает vtsls.
  rm -rf node_modules/vscode-langservers-extracted/node_modules/typescript
  # Удалённый пакет оставляет за собой висячие симлинки в .bin (tsc, tsserver). Сборщик
  # дистрибутива обходит дерево и проставляет время файлам — на висячей ссылке это падает
  # (проверено сборкой 7). Мёртвая ссылка бесполезна и без сборщика: ведёт в никуда.
  find node_modules -type l ! -exec test -e {} \; -print -delete
)
rm -rf extracted/servers/node && mkdir -p extracted/servers/node
cp -R servers-npm/node_modules extracted/servers/node/node_modules
printf 'Language servers (MIT/Apache-2.0) installed from a pinned package-lock.json; licences travel inside each package.\n' \
  > extracted/servers/node/README.txt

# --- Отладочные адаптеры: vscode-js-debug (TS/JS) и vscode-php-debug (Xdebug) ---
#
# Везём по решению владельца 01.09.2026: 1,2 и 1,8 МБ архивов за то, чтобы точка останова
# работала сразу после установки, как и переход к определению. Оба на Node — рантайм по-прежнему
# машинный. Свой установленный адаптер остаётся сильнее нашего: каталоги пользователя
# просматриваются раньше встроенного.
[ -f "js-debug-dap-v$JS_DEBUG_V.tar.gz" ] || curl -sL -o "js-debug-dap-v$JS_DEBUG_V.tar.gz" \
  "https://github.com/microsoft/vscode-js-debug/releases/download/v$JS_DEBUG_V/js-debug-dap-v$JS_DEBUG_V.tar.gz"
echo "$JS_DEBUG_SHA  js-debug-dap-v$JS_DEBUG_V.tar.gz" | shasum -a 256 -c -
rm -rf extracted/servers/dap/vibeJsDebug && mkdir -p extracted/servers/dap/vibeJsDebug
tar -xzf "js-debug-dap-v$JS_DEBUG_V.tar.gz" -C extracted/servers/dap/vibeJsDebug

[ -f "php-debug-$PHP_DEBUG_V.vsix" ] || curl -sL -o "php-debug-$PHP_DEBUG_V.vsix" \
  "https://github.com/xdebug/vscode-php-debug/releases/download/v$PHP_DEBUG_V/php-debug-$PHP_DEBUG_V.vsix"
echo "$PHP_DEBUG_SHA  php-debug-$PHP_DEBUG_V.vsix" | shasum -a 256 -c -
# vsix — это zip; в IDE нужен ТОЛЬКО extension/, остальное (иконки маркетплейса, манифест
# расширения VS Code) не нужно и весит. Распаковываем целиком во временный каталог и переносим
# подкаталог: шаблон `extension/*` в unzip на Windows не пересекает `/` и оставлял от адаптера
# четыре файла верхнего уровня без `out/phpDebug.js` (поймано гейтом дистрибутива 02.09.2026).
rm -rf extracted/servers/dap/vibePhpDebug extracted/vsix-tmp && mkdir -p extracted/servers/dap/vibePhpDebug extracted/vsix-tmp
unzip -qo "php-debug-$PHP_DEBUG_V.vsix" -d extracted/vsix-tmp
mv extracted/vsix-tmp/extension extracted/servers/dap/vibePhpDebug/extension
rm -rf extracted/vsix-tmp
# Обе лицензии MIT: текст обязан ехать рядом с копией. У php-debug он внутри extension/, у
# js-debug — отдельным файлом в архиве.
printf 'vscode-js-debug %s (MIT) and vscode-php-debug %s (MIT), bundled from project releases.\nLicences: vibeJsDebug/js-debug/LICENSE, vibePhpDebug/extension/LICENSE.txt\n' \
  "$JS_DEBUG_V" "$PHP_DEBUG_V" > extracted/servers/dap/README.txt
# Тот же капкан, что с npm-серверами: висячая ссылка валит обход дерева в сборщике дистрибутива.
find extracted/servers/dap -type l ! -exec test -e {} \; -print -delete
