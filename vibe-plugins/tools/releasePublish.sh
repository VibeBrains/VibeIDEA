#!/usr/bin/env bash
# Фаза 2 релиза: публикация ИМЕННО тех артефактов, которые проверены в фазе 1.
#
# Сверяется всё, что может разъехаться между фазами: версия, коммит, имя файла и его sha256. Любое
# расхождение — отказ, а не предупреждение: «наверное, тот же файл» — это то самое предположение,
# ради проверки которого штамп и существует.
#
# Использование:
#   ./vibe-plugins/tools/releasePublish.sh <файл-заметок> [--dry-run]
set -euo pipefail
cd "$(dirname "$0")/../.."

NOTES="${1:-}"
DRY="${2:-}"
REPO=VibeBrains/VibeIDEA
STAMP=out/vibeidea/artifacts/release-stamp.json

[ -f "$STAMP" ] || { echo "✖ нет штампа $STAMP — сначала фаза 1 (releaseStamp.sh)"; exit 1; }
[ -n "$NOTES" ] && [ -f "$NOTES" ] || { echo "✖ укажите файл заметок к релизу"; exit 1; }

read -r VERSION COMMIT FILE SHA <<<"$(python3 -c "
import json;d=json.load(open('$STAMP'));print(d['version'],d['commit'],d['file'],d['sha256'])
")"
DMG="out/vibeidea/artifacts/$FILE"
[ -f "$DMG" ] || { echo "✖ файла из штампа нет на диске: $DMG"; exit 1; }

fail=0
NOW_SHA=$(shasum -a 256 "$DMG" | awk '{print $1}')
[ "$NOW_SHA" = "$SHA" ] || { echo "✖ sha256 файла не совпадает со штампом — это ДРУГАЯ сборка"; fail=1; }
NOW_COMMIT=$(git rev-parse HEAD)
[ "$NOW_COMMIT" = "$COMMIT" ] || { echo "✖ HEAD ($NOW_COMMIT) не тот коммит, что проверен в фазе 1 ($COMMIT)"; fail=1; }
[ -z "$(git status --porcelain | head -1)" ] || { echo "✖ рабочее дерево грязное: публиковать надо ровно проверенное"; fail=1; }
if ! git rev-parse "$VERSION" >/dev/null 2>&1; then
  echo "✖ тега $VERSION нет — поставьте его на проверенный коммит"
  fail=1
else
  # Существования тега мало: он может стоять на другом коммите, и тогда опубликованное не совпадёт
  # с тем, что человек потом выкачает по тегу. Поймано собственным сухим прогоном 31.08.2026.
  TAG_COMMIT=$(git rev-parse "$VERSION^{commit}")
  [ "$TAG_COMMIT" = "$COMMIT" ] || { echo "✖ тег $VERSION указывает на $TAG_COMMIT, а проверялся $COMMIT"; fail=1; }
fi

if [ "$fail" -ne 0 ]; then
  echo "Публикация отменена: артефакты разошлись с проверенными."
  exit 1
fi

echo "  версия $VERSION, коммит $COMMIT, файл $FILE"
echo "  sha256 совпадает со штампом, дерево чистое, тег на месте"
if [ "$DRY" = "--dry-run" ]; then
  echo "Сухой прогон: всё сходится, публикация НЕ выполнялась."
  exit 0
fi

gh release create "$VERSION" --repo "$REPO" --title "VibeIDEA ${VERSION#v}" --notes-file "$NOTES"
gh release upload "$VERSION" "$DMG" --repo "$REPO"
gh release view "$VERSION" --repo "$REPO" --json assets --jq '.assets[] | {name, size, state}'
