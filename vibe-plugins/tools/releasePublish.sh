#!/usr/bin/env bash
# Фаза 2 релиза: публикация ИМЕННО тех артефактов, которые проверены в фазе 1.
#
# Сверяется всё, что может разъехаться между фазами: версия, коммит, имена файлов и их sha256. Любое
# расхождение — отказ, а не предупреждение: «наверное, тот же файл» — это то самое предположение,
# ради проверки которого штамп и существует.
#
# Релиз на GitHub один на версию, а сборок — по одной на ОС, и делаются они на разных машинах.
# Поэтому: релиза ещё нет — создаём его с заметками и заливаем файлы; релиз уже есть (создан сборкой
# под другую ОС) — доливаем файлы и приводим текст релиза к файлу заметок. Ассет с тем же именем
# в релизе — отказ: перетирание опубликованного делается руками и осознанно.
#
# Использование:
#   ./vibe-plugins/tools/releasePublish.sh <файл-заметок> [--dry-run]
set -euo pipefail
cd "$(dirname "$0")/../.."
. vibe-plugins/tools/pythonBin.sh

NOTES="${1:-}"
DRY="${2:-}"
REPO=VibeBrains/VibeIDEA
ARTIFACTS=out/vibeidea/artifacts
STAMP=$ARTIFACTS/release-stamp.json

[ -f "$STAMP" ] || { echo "✖ нет штампа $STAMP — сначала фаза 1 (releaseStamp.sh)"; exit 1; }
[ -n "$NOTES" ] && [ -f "$NOTES" ] || { echo "✖ укажите файл заметок к релизу"; exit 1; }

# Канал обновлений пишется В ФАЗЕ 2: до публикации объявлять версию нельзя — установленная IDE
# поведёт человека на страницу релиза, которой ещё нет.
read -r VERSION COMMIT PACKAGING_ONLY <<<"$("$PYTHON" -c "
import json;d=json.load(open('$STAMP'));print(d['version'],d['commit'],str(d.get('packagingOnly',False)).lower())
")"
# Причина правки продукта поверх тега — отдельной строкой: в ней пробелы, и в общий read она не лезет.
PRODUCT_FIX=$("$PYTHON" -c "
import json;print(json.load(open('$STAMP')).get('productFix',''))
")
# Канал обновлений: файл обязан описывать выпускаемую сборку и лежать в коммите ветки main.
# Проверка здесь, а не в штампе, потому что писать его раньше нельзя — установленная IDE прочитала
# бы объявление о версии, страницы релиза которой ещё нет.
BUILD_NUMBER=$("$PYTHON" -c "
import json;print(json.load(open('$STAMP')).get('buildNumber',''))
")
if [ -n "$BUILD_NUMBER" ]; then
  grep -q "number=\"$BUILD_NUMBER\" version=\"${VERSION#v}\"" updates/updates.xml 2>/dev/null || {
    echo "✖ updates/updates.xml не описывает сборку $BUILD_NUMBER / ${VERSION#v}."
    echo "  Выполните ./vibe-plugins/tools/releaseUpdatesXml.sh $VERSION, закоммитьте в main и повторите."
    exit 1
  }
fi

# Одна строка на файл: «имя sha256».
#
# Читаем циклом, а не mapfile: в macOS штатный bash — 3.2, где mapfile не существует, и релиз
# останавливался на первой же строке (найдено выпуском 0.4.0). Скрипт релиза обязан работать на
# машине, где релиз собирают.
ENTRIES=()
while IFS= read -r line; do
  [ -n "$line" ] && ENTRIES+=("$line")
done < <("$PYTHON" -c "
import json
for f in json.load(open('$STAMP'))['files']: print(f['file'], f['sha256'])
")

# Заметки проверяются ДО всего остального: без фразы поддержки релиз не выпускается, и узнавать
# об этом после заливки 800 МБ — поздно. Гейт перенят у VibeIDE (vibe-release-lint.js).
"$(dirname "$0")/checkVibeReleaseNotes.sh" "$("$PYTHON" -c "
import json;print(json.load(open('$STAMP'))['version'])")" "$NOTES" || {
  echo "Публикация отменена: заметки не прошли проверку."
  exit 1
}

fail=0
FILES=()
for entry in "${ENTRIES[@]}"; do
  read -r NAME SHA <<<"$entry"
  PATH_="$ARTIFACTS/$NAME"
  [ -f "$PATH_" ] || { echo "✖ файла из штампа нет на диске: $PATH_"; fail=1; continue; }
  NOW_SHA=$(shasum -a 256 "$PATH_" | awk '{print $1}')
  [ "$NOW_SHA" = "$SHA" ] || { echo "✖ sha256 файла $NAME не совпадает со штампом — это ДРУГАЯ сборка"; fail=1; }
  FILES+=("$PATH_")
done
NOW_COMMIT=$(git rev-parse HEAD)
if [ "$NOW_COMMIT" != "$COMMIT" ]; then
  # Единственное допустимое расхождение: коммиты канала обновлений, которые ПО ПРАВИЛУ пишутся в
  # фазе 2, то есть уже после штампа. Они не меняют ни строчки в продукте — `updates.xml` в
  # дистрибутив не попадает, — поэтому «HEAD ушёл вперёд» здесь не означает «собрано другое».
  # Проверяем это фактом, а не доверием: между штампом и HEAD не должно быть тронуто ничего, кроме
  # самого файла канала.
  CHANGED=$(git diff --name-only "$COMMIT" "$NOW_COMMIT" | sort -u)
  if [ "$CHANGED" = "updates/updates.xml" ] && git merge-base --is-ancestor "$COMMIT" "$NOW_COMMIT"; then
    echo "  HEAD впереди штампа на коммит канала обновлений — продукт тот же"
  else
    echo "✖ HEAD ($NOW_COMMIT) не тот коммит, что проверен в фазе 1 ($COMMIT)"
    [ -n "$CHANGED" ] && echo "  тронуто между ними: $(echo "$CHANGED" | tr '\n' ' ')"
    fail=1
  fi
fi
[ -z "$(git status --porcelain | head -1)" ] || { echo "✖ рабочее дерево грязное: публиковать надо ровно проверенное"; fail=1; }
if ! git rev-parse "$VERSION" >/dev/null 2>&1; then
  echo "✖ тега $VERSION нет — поставьте его на проверенный коммит"
  fail=1
else
  # Существования тега мало: он может стоять на другом коммите, и тогда опубликованное не совпадёт
  # с тем, что человек потом выкачает по тегу. Поймано собственным сухим прогоном 31.08.2026.
  TAG_COMMIT=$(git rev-parse "$VERSION^{commit}")
  if [ "$TAG_COMMIT" != "$COMMIT" ]; then
    # Допустимое расхождение: релиз уже вышел под другую ОС, а эта сборка — на потомке тега.
    # Штампу здесь не верят — правило проверяется заново, вместе с причиной правки продукта, если
    # она в штампе названа. Причина не «разрешение», а запись: её печатает и публикация.
    if [ "$PACKAGING_ONLY" = true ] \
       && ./vibe-plugins/tools/releasePackagingOnly.sh "$VERSION" "$COMMIT" \
            ${PRODUCT_FIX:+--product-fix "$PRODUCT_FIX"}; then
      :
    else
      echo "✖ тег $VERSION указывает на $TAG_COMMIT, а проверялся $COMMIT"; fail=1
    fi
  fi
fi

EXISTS=0
if gh release view "$VERSION" --repo "$REPO" --json assets --jq '.assets[].name' >"$ARTIFACTS/.published-assets" 2>/dev/null; then
  EXISTS=1
  for f in "${FILES[@]}"; do
    grep -qx "$(basename "$f")" "$ARTIFACTS/.published-assets" && { echo "✖ ассет $(basename "$f") уже опубликован в $VERSION — публикация не перетирает; удалите его осознанно: gh release delete-asset"; fail=1; }
  done
fi
# Упаковочный потомок имеет смысл только как дополнение к уже вышедшему релизу: первый выпуск
# версии делается строго с тега.
if [ "$PACKAGING_ONLY" = true ] && [ "$EXISTS" -eq 0 ]; then
  echo "✖ релиза $VERSION ещё нет: первая публикация версии делается только с самого тега"; fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "Публикация отменена: артефакты разошлись с проверенными."
  exit 1
fi

echo "  версия $VERSION, коммит $COMMIT"
for f in "${FILES[@]}"; do echo "  файл $(basename "$f") — sha256 совпадает со штампом"; done
echo "  дерево чистое, тег на месте$([ "$PACKAGING_ONLY" = true ] && echo " (сборка на потомке тега)")"
[ -n "$PRODUCT_FIX" ] && echo "  ВНИМАНИЕ: артефакты этой ОС содержат правку продукта поверх тега — $PRODUCT_FIX"
if [ "$EXISTS" -eq 1 ]; then
  echo "  релиз $VERSION уже существует: файлы будут долиты, текст релиза — приведён к $NOTES"
else
  echo "  релиза $VERSION ещё нет: будет создан с заметками $NOTES"
fi
if [ "$DRY" = "--dry-run" ]; then
  echo "Сухой прогон: всё сходится, публикация НЕ выполнялась."
  exit 0
fi

if [ "$EXISTS" -eq 1 ]; then
  gh release edit "$VERSION" --repo "$REPO" --notes-file "$NOTES"
else
  gh release create "$VERSION" --repo "$REPO" --title "VibeIDEA ${VERSION#v}" --notes-file "$NOTES"
fi
for f in "${FILES[@]}"; do
  gh release upload "$VERSION" "$f" --repo "$REPO"
done
gh release view "$VERSION" --repo "$REPO" --json assets --jq '.assets[] | {name, size, state}'
