#!/usr/bin/env bash
# Фаза 1 релиза: штамп собранных артефактов.
#
# Зачем разделять сборку и публикацию: между «собрал и проверил» и «выложил» проходит время, и в
# него помещается ещё одна сборка, правка в рабочем дереве или чужая ветка. Публиковать после этого
# значит выкладывать не то, что проверяли, — и узнать об этом можно только от пользователя.
#
# Штамп фиксирует, ЧТО именно проверено: версия, коммит, чистота дерева, имена файлов и их sha256.
# Фаза 2 (releasePublish.sh) откажется публиковать что-либо, не совпадающее со штампом.
#
# Артефакты берутся по ОС сборки: на macOS — dmg, на Windows — инсталлятор .exe и архив .win.zip.
# Плюс кросс-собранные: с выпуска 0.5.0 Linux (.tar.gz) и Windows (.exe и .win.zip) собираются на
# маке (решения владельца 10.09.2026, manuals/release.md), и они обязаны попасть в штамп — фаза 2
# публикует ровно то, что в нём записано, а артефакта мимо штампа для публикации не существует.
# Гейт дистрибутива прогоняется по образу, который он умеет открыть (dmg или zip); .exe проверяется
# тихой установкой перед штампом (manuals/release.md) — сборка своё сравнение exe и zip в dev-режиме
# пропускает.
#
# Использование: ./vibe-plugins/tools/releaseStamp.sh v0.1.0
set -euo pipefail
cd "$(dirname "$0")/../.."

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "✖ укажите версию: releaseStamp.sh vX.Y.Z [--product-fix \"<причина>\"]"; exit 1; }
# Правка продукта поверх уже выпущенного тега — только названная вслух; разбор — releasePackagingOnly.sh.
FIX_MODE="${2:-}"; FIX_REASON="${3:-}"

# Версия в «О программе» обязана совпадать с тегом. Разойтись им ничего не мешает — это два разных
# файла, — а расхождение обнаруживает пользователь, который в issue пишет не ту версию, что стоит.
INFO=vibeidea-customization/resources/idea/VibeIdeaApplicationInfo.xml
# Читаем именно full: major/minor кодируют линию платформы (иначе сборка падает), а версия
# продукта живёт в full — это то, что видно в «О программе».
XML_VERSION=$(grep -o 'full="[^"]*"' "$INFO" | head -1 | sed 's/full="//; s/"//')
if [ "${VERSION#v}" != "$XML_VERSION" ]; then
  echo "✖ версия в «О программе» ($XML_VERSION) не совпадает с выпускаемой (${VERSION#v})."
  echo "  Поправьте $INFO — иначе установленная сборка будет называть себя чужим номером."
  exit 1
fi

ARTIFACTS=out/vibeidea/artifacts
newest() { ls -t "$ARTIFACTS"/$1 2>/dev/null | head -1 || true; }
LINUX=""; WINZIP=""; WINEXE=""
case "$(uname -s)" in
  Darwin)
    OS=macos
    IMAGE=$(newest '*.dmg')
    FILES=("$IMAGE")
    LINUX=$(newest '*.tar.gz')
    if [ -n "$LINUX" ]; then FILES+=("$LINUX"); fi
    WINZIP=$(newest '*.win.zip')
    if [ -n "$WINZIP" ]; then FILES+=("$WINZIP"); fi
    WINEXE=$(newest '*.exe')
    if [ -n "$WINEXE" ]; then FILES+=("$WINEXE"); fi
    ;;
  MINGW*|MSYS*|CYGWIN*)
    OS=windows
    IMAGE=$(newest '*.win.zip')
    EXE=$(newest '*.exe')
    [ -n "$EXE" ] || { echo "✖ нет инсталлятора .exe в $ARTIFACTS — сборка Windows не завершилась"; exit 1; }
    FILES=("$EXE" "$IMAGE")
    ;;
  *) echo "✖ релизные артефакты для $(uname -s) не описаны"; exit 1 ;;
esac
[ -n "$IMAGE" ] || { echo "✖ нет собранного образа в $ARTIFACTS — сначала соберите инсталлятор"; exit 1; }

# Номер сборки и канал обновлений. SNAPSHOT платформа читает как Integer.MAX_VALUE — такая сборка
# считает себя новее всего, что мы когда-либо опубликуем, и канал для неё молчит навсегда.
# updates.xml пишется releaseUpdatesXml.sh по этому же артефакту и обязан лежать в коммите.
INFO_JSON=$(ls -t "$ARTIFACTS"/*.product-info.json 2>/dev/null | head -1 || true)
[ -n "$INFO_JSON" ] || { echo "✖ нет product-info.json рядом с артефактом — сборка не завершилась"; exit 1; }
BUILD_NUMBER=$(grep -o '"buildNumber" *: *"[^"]*"' "$INFO_JSON" | sed 's/.*: *"//; s/"//')
case "$BUILD_NUMBER" in
  *SNAPSHOT*) echo "✖ номер сборки $BUILD_NUMBER — SNAPSHOT: релиз обязан нести настоящий номер (см. VibeBuildNumber)"; exit 1 ;;
esac
# Релиз собирается ИЗ main — правило одно на оба продукта VibeBrains (у VibeIDE оно записано
# прецедентом v1.5.2–v1.5.4: чинили одну платформу, а в «фикс-релиз» с main уехали невыпущенные
# фичи). Собранное из next штампуется коммитом, которого в релизной ветке ещё нет, и «выпущено»
# перестаёт значить «лежит в main».
BRANCH=$(git rev-parse --abbrev-ref HEAD)
[ "$BRANCH" = "main" ] || {
  echo "✖ фаза 1 идёт из ветки $BRANCH, а релизы собираются из main."
  echo "  Сначала: git checkout main && git merge --ff-only next — потом сборка и штамп."
  exit 1
}

# updates.xml здесь НЕ требуется: он пишется в фазе 2, непосредственно перед публикацией.
# Иначе канал обновлений объявляет версию, релиза которой ещё нет, и установленная IDE ведёт
# человека на страницу 404 — ровно в том окне, ради которого фазы и разделены.

# Грязное дерево означает, что собранное и лежащее в git — разные вещи, и штамп соврал бы о коммите.
DIRTY=$(git status --porcelain | head -1)
[ -z "$DIRTY" ] || { echo "✖ рабочее дерево грязное: штамп привязывает сборку к коммиту, а коммит сейчас не описывает то, что собрано"; exit 1; }

echo "  проверяю дистрибутив перед штампом"
./vibe-plugins/tools/checkVibeDist.sh "$IMAGE" >/dev/null || { echo "✖ гейт дистрибутива не прошёл — штамповать нечего"; exit 1; }
# Кросс-собранные проверяются тем же гейтом — по тому, что он умеет открыть. У .tar.gz проверка
# половинная: состав и индекс плагинов да, «серверы запускаются» нет — линуксовые бинари здесь
# выполнить нечем (manuals/release.md). Инсталлятор .exe гейт не открывает вовсе: его проверяют
# тихой установкой на Windows, и этот шаг остаётся ручным — штамп о нём говорит вслух.
for extra in "$LINUX" "$WINZIP"; do
  [ -n "$extra" ] || continue
  echo "  проверяю $(basename "$extra")"
  ./vibe-plugins/tools/checkVibeDist.sh "$extra" >/dev/null || { echo "✖ гейт дистрибутива не прошёл на $(basename "$extra")"; exit 1; }
done
if [ -n "$WINEXE" ]; then
  echo "  ⚠ $(basename "$WINEXE") гейтом не проверяется: тихая установка на Windows — manuals/release.md"
fi

COMMIT=$(git rev-parse HEAD)
# Тег уже есть (релиз под другую ОС вышел раньше) — сборка обязана быть либо ровно на нём, либо на
# его упаковочном потомке; правило и проверка — releasePackagingOnly.sh.
PACKAGING_ONLY=false
if git rev-parse -q --verify "$VERSION^{commit}" >/dev/null 2>&1 && [ "$(git rev-parse "$VERSION^{commit}")" != "$COMMIT" ]; then
  ./vibe-plugins/tools/releasePackagingOnly.sh "$VERSION" "$COMMIT" ${FIX_MODE:+"$FIX_MODE" "$FIX_REASON"} \
    || { echo "✖ штамповать нечего: сборка не на теге $VERSION и не на его упаковочном потомке"; exit 1; }
  PACKAGING_ONLY=true
fi
STAMP=$ARTIFACTS/release-stamp.json
{
  # buildNumber попадает в штамп ради фазы 2: там канал обновлений сверяется с ТЕМ ЖЕ номером,
  # который прошёл гейт дистрибутива, а не с тем, что окажется в дереве через час.
  printf '{\n  "version": "%s",\n  "commit": "%s",\n  "buildNumber": "%s",\n  "packagingOnly": %s,\n  "productFix": "%s",\n  "os": "%s",\n  "files": [\n' \
    "$VERSION" "$COMMIT" "$BUILD_NUMBER" "$PACKAGING_ONLY" "$FIX_REASON" "$OS"
  for i in "${!FILES[@]}"; do
    f="${FILES[$i]}"
    sha=$(shasum -a 256 "$f" | awk '{print $1}')
    size=$(wc -c < "$f" | tr -d ' ')
    sep=$([ "$i" -lt $((${#FILES[@]} - 1)) ] && echo "," || echo "")
    printf '    { "file": "%s", "sha256": "%s", "size": %s }%s\n' "$(basename "$f")" "$sha" "$size" "$sep"
  done
  printf '  ],\n  "stampedAt": "%s",\n  "distGate": "passed"\n}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$STAMP"

echo "  версия:  $VERSION"
echo "  коммит:  $COMMIT$([ "$PACKAGING_ONLY" = true ] && echo " (потомок тега $VERSION)")"
[ -n "$FIX_REASON" ] && echo "  правка продукта поверх тега: $FIX_REASON"
for f in "${FILES[@]}"; do
  echo "  файл:    $(basename "$f") ($(wc -c < "$f" | tr -d ' ') байт)"
  echo "  sha256:  $(shasum -a 256 "$f" | awk '{print $1}')"
done
echo "Штамп записан: $STAMP"
