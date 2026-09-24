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
#
# Every pipeline assigned below ends in `|| true`: under `set -euo pipefail` a failed pipeline inside an assignment ends
# the script on the spot, and the check right after it, the one that says what went wrong, never gets to speak.
BASE=$(grep -oE '`[0-9a-f]{10}`' FORK_CHANGES.md | head -1 | tr -d '`' || true)
[ -n "$BASE" ] || { say "✖ в FORK_CHANGES.md не нашлась текущая база апстрима"; exit 1; }

REMOTE_HEAD=$(git ls-remote upstream master 2>/dev/null | awk '{print $1}' | head -1 || true)
[ -n "$REMOTE_HEAD" ] || { say "✖ не удалось спросить upstream (сеть или remote)"; exit 1; }

if [ "${REMOTE_HEAD:0:10}" = "$BASE" ]; then
  say "  база совпадает с upstream/master ($BASE) — отставания нет"
  exit 0
fi

# The base is an upstream commit, and a clone of our repository alone may well lack it: a sync that landed as one
# ordinary commit leaves the base out of our history entirely, and a merged base sits deeper than the single commit the
# CI checkout fetches. Locally it is here once upstream has been fetched. Otherwise upstream itself is asked: GitHub
# resolves the short id the registry keeps. Only a commit upstream does not know either is a registry problem; a
# question nobody answered measures nothing.
BASE_DATE=$(git log -1 --format=%ct "$BASE" 2>/dev/null || echo "")
BASE_DAY=$(git log -1 --format=%cd --date=short "$BASE" 2>/dev/null || echo "")
if [ -z "$BASE_DATE" ]; then
  UPSTREAM_REPO=$(git remote get-url upstream 2>/dev/null | sed -nE 's#^https://github\.com/([^/]+/[^/]+)$#\1#p' | sed 's/\.git$//' || true)
  if ! command -v gh >/dev/null 2>&1 || [ -z "$UPSTREAM_REPO" ]; then
    say "✖ коммита базы $BASE в этом клоне нет, а спросить апстрим нечем (нужны gh и remote upstream на GitHub)"
    say "  отставание не измерено; локально хватит git fetch upstream"
    exit 1
  fi
  ASKED=$(gh api "repos/$UPSTREAM_REPO/commits/$BASE" \
    --jq '(.commit.committer.date | fromdateiso8601 | tostring) + " " + .commit.committer.date[0:10]' 2>&1) || {
    case "$ASKED" in
      *"(HTTP 422)"*|*"(HTTP 404)"*)
        say "✖ коммита базы $BASE нет ни в этом клоне, ни у апстрима — в реестре опечатка или синк делался мимо реестра" ;;
      *)
        say "✖ коммита базы $BASE в этом клоне нет, а апстрим не ответил — отставание не измерено:"
        printf '%s\n' "$ASKED" | tail -1 | sed 's/^/    /' ;;
    esac
    exit 1
  }
  BASE_DATE=${ASKED%% *}
  BASE_DAY=${ASKED#* }
fi

NOW=$(date +%s)
DAYS=$(( (NOW - BASE_DATE) / 86400 ))
say "  база:            $BASE ($BASE_DAY)"
say "  upstream/master: ${REMOTE_HEAD:0:10}"
say "  отставание:      $DAYS дн. (порог $LIMIT_DAYS)"

# Релизные теги — то, чем синк ведётся ПО ПЛЕЙБУКУ (FORK_CHANGES.md): «синк по тегам релизов
# апстрима, не по HEAD master». Пока их не показывали, инструмент отвечал на другой вопрос:
# «насколько ушёл master», — а на вопрос «какой тег брать следующим» человек шёл искать руками.
#
# Все теги не тащим (их около 2800, и они тянут релизные ветки): здесь только ИМЕНА через
# ls-remote, а нужный тег забирается точечно — команда печатается ниже.
LINE=$(sed -E 's/^([0-9]+)\..*/\1/' build.txt 2>/dev/null | head -1 || true)
TAGS=$(git ls-remote --tags upstream 'refs/tags/idea/*' 2>/dev/null \
       | grep -v '\^{}' | awk '{print $2}' | sed 's|refs/tags/||' | sort -V || true)
if [ -n "$TAGS" ]; then
  # Тег ЧУЖОЙ линии брать нельзя: наша база стоит на 263 (2026.3), а последний стабильный релиз
  # апстрима на момент написания — 2026.2.2, то есть предыдущая линия. Слить её в наше дерево
  # значит откатить платформу назад, и такой совет хуже отсутствия совета.
  # Номер линии переводится в версию по правилу апстрима: 263 → 2026.3.
  VERSION=""
  case "$LINE" in
    [0-9][0-9][0-9]) VERSION="20${LINE%?}.${LINE#??}" ;;
  esac
  # Стабильный релиз — без суффикса: -rc, -preview и -eap в основу форка не ставим.
  MINE=$(printf '%s\n' "$TAGS" | { grep -E "^idea/${VERSION}(\.[0-9]+)?$" || true; } | tail -1)
  MINE_ANY=$(printf '%s\n' "$TAGS" | { grep -E "^idea/${VERSION}([.-]|$)" || true; } | tail -1)
  STABLE=$(printf '%s\n' "$TAGS" | { grep -E '^idea/[0-9]+\.[0-9]+(\.[0-9]+)?$' || true; } | tail -1)
  say "  линия платформы: ${LINE:-неизвестна} (build.txt${VERSION:+ → $VERSION})"
  if [ -n "$MINE" ]; then
    say "  последний стабильный тег НАШЕЙ линии: $MINE"
    say "  взять точечно: git fetch upstream tag $MINE --no-tags"
  elif [ -n "$MINE_ANY" ]; then
    # Так выглядит нормальная жизнь на свежей линии: релиза ещё нет, есть только EAP.
    say "  стабильного релиза линии ${VERSION} у апстрима ещё нет; самый свежий тег линии: $MINE_ANY"
    say "  синкаться по нему — решение владельца: EAP не релиз."
    say "  (последний стабильный тег вообще — ${STABLE:-нет} — ЧУЖАЯ линия, в нашу базу не мержится)"
  else
    say "  тегов линии ${VERSION:-?} у апстрима не нашлось — синк планировать по FORK_CHANGES.md вручную"
  fi
else
  say "  теги апстрима спросить не удалось — синк по тегам придётся планировать вручную"
fi

# Порог считается только когда синкаться ЕСТЬ ПО ЧЕМУ. Решение владельца 08.09.2026: синкаемся
# только по стабильным релизам своей линии, всё остальное — руками и по требованию. Пока такого
# тега нет, «пора синкаться» — требование невыполнимого, а инструмент, который каждый месяц зовёт
# к работе, которую делать нельзя, перестают читать.
if [ "$DAYS" -gt "$LIMIT_DAYS" ] && [ -n "${MINE:-}" ]; then
  say "✖ пора синкаться: плейбук — FORK_CHANGES.md, раздел «Инструкция по upstream sync»."
  say "  Помнить про отдельный клон android/ — он в дифф апстрима не входит."
  exit 1
fi
if [ "$DAYS" -gt "$LIMIT_DAYS" ]; then
  say "Порог по дням превышен, но стабильного тега нашей линии нет — ждём релиз (решение №57)."
else
  say "Отставание в пределах порога."
fi
