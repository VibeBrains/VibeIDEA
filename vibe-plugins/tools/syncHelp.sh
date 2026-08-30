#!/usr/bin/env bash
# Кладёт документацию продукта внутрь плагина, чтобы агент мог её прочитать в собранной IDE.
#
# Почему копией, а не ссылкой: ресурсы плагина собираются из его собственной папки, и симлинк в
# дистрибутиве превратится в битую ссылку на машине пользователя. Копия честнее, а расхождение
# копии с источником ловит тест HelpBundleTest — он и есть гейт этой синхронизации.
set -euo pipefail
cd "$(dirname "$0")/../.."

SRC_DOCS=docs/vibe
DEST=vibe-plugins/vibe-agent/resources/help

rm -rf "$DEST"
mkdir -p "$DEST/manuals"

cp "$SRC_DOCS/functional.md" "$DEST/functional.md"
cp "$SRC_DOCS/agentsGuide.md" "$DEST/agentsGuide.md"
cp "$SRC_DOCS/manuals/"*.md "$DEST/manuals/"

# Индекс — файлом, а не списком в коде: список в коде уже разошёлся с папкой (16 против 18),
# и заметить это можно было только по тому, что агент не находит два мануала.
INDEX="$DEST/index.txt"
( cd "$DEST" && find . -name '*.md' | sed 's|^\./||' | sort ) > "$INDEX"

count=$(grep -c . "$INDEX")
printf 'Справка в сборке: %s файлов в %s\n' "$count" "$DEST"
