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
# Гейт дистрибутива прогоняется по образу, который он умеет открыть (dmg или zip); .exe проверяется
# тихой установкой перед штампом (manuals/release.md) — сборка своё сравнение exe и zip в dev-режиме
# пропускает.
#
# Использование: ./vibe-plugins/tools/releaseStamp.sh v0.1.0
set -euo pipefail
cd "$(dirname "$0")/../.."

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "✖ укажите версию: releaseStamp.sh vX.Y.Z"; exit 1; }

ARTIFACTS=out/vibeidea/artifacts
newest() { ls -t "$ARTIFACTS"/$1 2>/dev/null | head -1 || true; }
case "$(uname -s)" in
  Darwin)
    OS=macos
    IMAGE=$(newest '*.dmg')
    FILES=("$IMAGE")
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

# Грязное дерево означает, что собранное и лежащее в git — разные вещи, и штамп соврал бы о коммите.
DIRTY=$(git status --porcelain | head -1)
[ -z "$DIRTY" ] || { echo "✖ рабочее дерево грязное: штамп привязывает сборку к коммиту, а коммит сейчас не описывает то, что собрано"; exit 1; }

echo "  проверяю дистрибутив перед штампом"
./vibe-plugins/tools/checkVibeDist.sh "$IMAGE" >/dev/null || { echo "✖ гейт дистрибутива не прошёл — штамповать нечего"; exit 1; }

COMMIT=$(git rev-parse HEAD)
# Тег уже есть (релиз под другую ОС вышел раньше) — сборка обязана быть либо ровно на нём, либо на
# его упаковочном потомке; правило и проверка — releasePackagingOnly.sh.
PACKAGING_ONLY=false
if git rev-parse -q --verify "$VERSION^{commit}" >/dev/null 2>&1 && [ "$(git rev-parse "$VERSION^{commit}")" != "$COMMIT" ]; then
  ./vibe-plugins/tools/releasePackagingOnly.sh "$VERSION" "$COMMIT" || { echo "✖ штамповать нечего: сборка не на теге $VERSION и не на его упаковочном потомке"; exit 1; }
  PACKAGING_ONLY=true
fi
STAMP=$ARTIFACTS/release-stamp.json
{
  printf '{\n  "version": "%s",\n  "commit": "%s",\n  "packagingOnly": %s,\n  "os": "%s",\n  "files": [\n' "$VERSION" "$COMMIT" "$PACKAGING_ONLY" "$OS"
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
echo "  коммит:  $COMMIT$([ "$PACKAGING_ONLY" = true ] && echo " (упаковочный потомок тега $VERSION)")"
for f in "${FILES[@]}"; do
  echo "  файл:    $(basename "$f") ($(wc -c < "$f" | tr -d ' ') байт)"
  echo "  sha256:  $(shasum -a 256 "$f" | awk '{print $1}')"
done
echo "Штамп записан: $STAMP"
