#!/usr/bin/env bash
# Насколько наша база отстала от апстрима.
#
# Форк живёт синками, и вопрос не «отстали ли» — отстали всегда, — а «настолько ли, что пора».
# Без замера ответ даётся ощущением, а ощущение всегда «да ещё нормально»: синк дорогой, и его
# откладывают, пока он не станет ещё дороже.
#
# Порог в днях задаётся аргументом (по умолчанию 30). Превышение — код возврата 1: это не поломка,
# а повод запланировать работу, поэтому в CI он вешается на расписание, а не на каждый пуш.
set -euo pipefail
cd "$(dirname "$0")/../.."

LIMIT_DAYS="${1:-30}"
say() { printf '%s\n' "$1"; }

# Базу берём из реестра отличий, а не из git: там она записана человеком и сверяется глазами.
BASE=$(grep -oE '`[0-9a-f]{10}`' FORK_CHANGES.md | head -1 | tr -d '`')
[ -n "$BASE" ] || { say "✖ в FORK_CHANGES.md не нашлась текущая база апстрима"; exit 1; }

REMOTE_HEAD=$(git ls-remote upstream master 2>/dev/null | awk '{print $1}' | head -1)
[ -n "$REMOTE_HEAD" ] || { say "✖ не удалось спросить upstream (сеть или remote)"; exit 1; }

if [ "${REMOTE_HEAD:0:10}" = "$BASE" ]; then
  say "  база совпадает с upstream/master ($BASE) — отставания нет"
  exit 0
fi

# Дата базы есть локально: сам коммит у нас в репозитории.
BASE_DATE=$(git log -1 --format=%ct "$BASE" 2>/dev/null || echo "")
[ -n "$BASE_DATE" ] || { say "✖ коммита базы $BASE нет локально — синк делался мимо реестра?"; exit 1; }

NOW=$(date +%s)
DAYS=$(( (NOW - BASE_DATE) / 86400 ))
say "  база:            $BASE ($(git log -1 --format=%cd --date=short "$BASE"))"
say "  upstream/master: ${REMOTE_HEAD:0:10}"
say "  отставание:      $DAYS дн. (порог $LIMIT_DAYS)"

if [ "$DAYS" -gt "$LIMIT_DAYS" ]; then
  say "✖ пора синкаться: плейбук — FORK_CHANGES.md, раздел «Инструкция по upstream sync»."
  say "  Помнить про отдельный клон android/ — он в дифф апстрима не входит."
  exit 1
fi
say "Отставание в пределах порога."
