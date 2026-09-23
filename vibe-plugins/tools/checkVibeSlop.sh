#!/usr/bin/env bash
# Гейт нейрослопа в документации: храповик по находкам детектора текста в docs/vibe и README.md.
#
# Детектор тот же, что в IDE (textSlopCli — код vibe-agent, а не копия в скрипте), каталог тот же,
# что уезжает в сборку, а правки дома — vibe-plugins/tools/slopHouseStyle.json: там выключено то,
# что у нас норма (жирные ярлыки, короткие разделы, строка на мысль).
#
# Старый долг здесь не переписывается разом. Храповик делает другое: число находок не может
# вырасти, а когда падает, планка опускается сама. Новый документ со штампами уронит гейт,
# вычищенный старый опустит планку.
#
# Разбор находок по файлам — та же команда без --count:
#   ./bazel.cmd run //vibe-plugins/vibe-agent:textSlopCli -- --overrides "$PWD/vibe-plugins/tools/slopHouseStyle.json" "$PWD/docs/vibe/<файл>.md"
set -euo pipefail
cd "$(dirname "$0")/../.."

RATCHET_FILE=vibe-plugins/tools/slopRatchet.txt
HOUSE_STYLE=vibe-plugins/tools/slopHouseStyle.json
say() { printf '%s\n' "$1"; }

# `bazel run` запускает команду из своего каталога, поэтому пути — абсолютные.
# Код выхода берётся у самой команды: 2 значит «сломано» (нет файла, не читается), и считать тогда нечего.
set +e
OUT=$(./bazel.cmd run //vibe-plugins/vibe-agent:textSlopCli -- --count --overrides "$PWD/$HOUSE_STYLE" "$PWD/docs/vibe" "$PWD/README.md" 2>&1)
CODE=$?
set -e
count=$(printf '%s\n' "$OUT" | sed -n 's/^SLOP_FINDINGS=\([0-9][0-9]*\)$/\1/p' | tail -1)
if [ "$CODE" -ne 0 ] || [ -z "$count" ]; then
  printf '%s\n' "$OUT" | tail -20
  say "✖ детектор текста не посчитал находки (код $CODE)"
  say "Гейт нейрослопа: ПРОВАЛЕН"
  exit 1
fi

# Планка обязана существовать: подставлять текущее число при её отсутствии значит пропускать любой
# рост, то есть держать гейт, который нельзя провалить.
if [ ! -f "$RATCHET_FILE" ]; then
  say "✖ нет файла планки $RATCHET_FILE — создайте его с текущим числом: echo $count > $RATCHET_FILE"
  exit 1
fi
limit=$(cat "$RATCHET_FILE")

if [ "$count" -gt "$limit" ]; then
  say "✖ находок нейрослопа в документации: $count, разрешено не больше $limit."
  say "  Файлы с находками (число, путь):"
  printf '%s\n' "$OUT" | awk -F'\t' 'NF == 2 && $1 ~ /^[0-9]+$/' | sort -rn | head -15 | sed 's/^/    /' || true
  say "  Разбор строк — команда из шапки этого скрипта. Порядок правки — навык anti-slop;"
  say "  находку, с которой вы не согласны, отметьте в тексте: <!-- slop-ignore ID — причина -->"
  say "Гейт нейрослопа: ПРОВАЛЕН"
  exit 1
elif [ "$count" -lt "$limit" ]; then
  say "  храповик нейрослопа: $count (было $limit) — опускаю планку"
  printf '%s\n' "$count" > "$RATCHET_FILE"
else
  say "  храповик нейрослопа: $count, планка $limit"
fi
say "Гейт нейрослопа: документация не хуже, чем была"
