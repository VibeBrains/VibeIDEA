#!/bin/sh
# Одна команда, собирающая артефакты VibeIDEA на macOS и Linux.
#
# Windows-близнец — vibeidea-build.bat. Два простых файла вместо одного «двойного» скрипта
# намеренно: трюк с bash+батником в одном файле нельзя проверить ни `sh -n`, ни глазами, а
# проверка сборочного скрипта нужнее его краткости.
#
# Смысл: на новой машине не надо помнить порядок — сперва зависимости по закреплённым версиям,
# потом сборка, потом «где лежит результат». Порядок, который надо помнить, однажды выполнят
# наполовину.
set -eu
root="$(cd "$(dirname "$0")"; pwd)"

echo "== 1/3  зависимости по закреплённым версиям"
sh "$root/vibe-plugins/deps/download.sh"

echo "== 2/3  сборка инсталляторов под текущую ОС"
"$root/vibeidea-installers.cmd" -Dintellij.build.target.os=current -Dintellij.build.target.arch=current "$@"

echo "== 3/3  готово"
ls -la "$root/out/vibeidea/artifacts" | grep -Ei "\.dmg|\.exe|\.zip|\.tar\.gz" || true
echo
echo "Проверка дистрибутива: ./vibe-plugins/tools/checkVibeDist.sh"
