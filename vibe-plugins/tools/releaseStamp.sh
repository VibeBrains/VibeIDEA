#!/usr/bin/env bash
# Фаза 1 релиза: штамп собранных артефактов.
#
# Зачем разделять сборку и публикацию: между «собрал и проверил» и «выложил» проходит время, и в
# него помещается ещё одна сборка, правка в рабочем дереве или чужая ветка. Публиковать после этого
# значит выкладывать не то, что проверяли, — и узнать об этом можно только от пользователя.
#
# Штамп фиксирует, ЧТО именно проверено: версия, коммит, чистота дерева, имя файла и его sha256.
# Фаза 2 (releasePublish.sh) откажется публиковать что-либо, не совпадающее со штампом.
#
# Использование: ./vibe-plugins/tools/releaseStamp.sh v0.1.0
set -euo pipefail
cd "$(dirname "$0")/../.."

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "✖ укажите версию: releaseStamp.sh vX.Y.Z"; exit 1; }

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

DMG=$(ls -t out/vibeidea/artifacts/*.dmg 2>/dev/null | head -1 || true)
[ -n "$DMG" ] || { echo "✖ нет собранного dmg в out/vibeidea/artifacts — сначала соберите инсталлятор"; exit 1; }

# Грязное дерево означает, что собранное и лежащее в git — разные вещи, и штамп соврал бы о коммите.
DIRTY=$(git status --porcelain | head -1)
[ -z "$DIRTY" ] || { echo "✖ рабочее дерево грязное: штамп привязывает сборку к коммиту, а коммит сейчас не описывает то, что собрано"; exit 1; }

echo "  проверяю дистрибутив перед штампом"
./vibe-plugins/tools/checkVibeDist.sh "$DMG" >/dev/null || { echo "✖ гейт дистрибутива не прошёл — штамповать нечего"; exit 1; }

SHA=$(shasum -a 256 "$DMG" | awk '{print $1}')
COMMIT=$(git rev-parse HEAD)
SIZE=$(wc -c < "$DMG" | tr -d ' ')
STAMP=out/vibeidea/artifacts/release-stamp.json

cat > "$STAMP" <<JSON
{
  "version": "$VERSION",
  "commit": "$COMMIT",
  "file": "$(basename "$DMG")",
  "sha256": "$SHA",
  "size": $SIZE,
  "stampedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "distGate": "passed"
}
JSON

echo "  версия:  $VERSION"
echo "  коммит:  $COMMIT"
echo "  файл:    $(basename "$DMG") ($SIZE байт)"
echo "  sha256:  $SHA"
echo "Штамп записан: $STAMP"
