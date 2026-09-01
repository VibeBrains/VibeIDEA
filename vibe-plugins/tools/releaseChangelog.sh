#!/usr/bin/env bash
# Черновик заметок к релизу: всё, что накопилось с последнего ОПУБЛИКОВАННОГО релиза.
#
# Базлайн — не последний тег, а последний тег, который реально виден людям. Тег ставится в фазе
# «тег и публикация», а публикация может не состояться: сборка не прошла гейт дистрибутива,
# владелец отложил выпуск, нашёлся дефект. Тогда версия существует в git и отсутствует на странице
# релизов, и заметки, отсчитанные от неё, молча теряют всё, что в неё входило. Скачавший увидит
# продукт, изменившийся сильнее, чем сказано.
#
# Поэтому базлайн берётся у GitHub (`gh release list`), а не у git, а неопубликованные теги между
# базлайном и HEAD выводятся отдельным списком — их содержимое обязано войти в текущие заметки.
#
# Использование: ./vibe-plugins/tools/releaseChangelog.sh [vX.Y.Z]
set -euo pipefail
cd "$(dirname "$0")/../.."

REPO=VibeBrains/VibeIDEA
VERSION="${1:-}"

# Свои файлы. Синк с апстримом приносит тысячи чужих коммитов, и ченджлог без этого фильтра
# перестанет читаться на первом же синке — а состав продукта определяет только наш код.
OURS=(vibe-plugins docs/vibe .github/workflows/vibeGates.yml .github/workflows/vibeAudit.yml \
      build/src/org/jetbrains/intellij/build/VibeIdeaProperties.kt README.md FORK_CHANGES.md CLAUDE.md)

BASE_TAG=$(gh release list --repo "$REPO" --limit 100 --json tagName,isDraft,isPrerelease \
  --jq 'map(select(.isDraft|not)) | .[0].tagName' 2>/dev/null || true)

if [ -z "$BASE_TAG" ] || [ "$BASE_TAG" = "null" ]; then
  echo "  опубликованных релизов нет — базлайн от первого коммита нашего кода"
  RANGE=$(git rev-list --max-parents=0 HEAD | tail -1)..HEAD
  BASE_TAG="(нет)"
else
  git rev-parse "$BASE_TAG" >/dev/null 2>&1 \
    || { echo "✖ опубликованный тег $BASE_TAG не найден локально — подтяните теги: git fetch --tags"; exit 1; }
  RANGE="$BASE_TAG..HEAD"
fi

echo "  базлайн: $BASE_TAG (последний опубликованный)"
[ -n "$VERSION" ] && echo "  готовится: $VERSION"

# Теги, которые есть в git, но не вышли на страницу релизов. Молчание здесь и есть та потеря,
# ради которой скрипт написан.
PUBLISHED=$(gh release list --repo "$REPO" --limit 100 --json tagName --jq '.[].tagName' 2>/dev/null || true)
SKIPPED=""
# Только версионные теги: точки восстановления перед синком (`sync-base-before`) релизами не
# являются, и предупреждение о них научило бы не читать предупреждение.
for t in $(git tag --merged HEAD --list 'v[0-9]*' 2>/dev/null); do
  [ "$t" = "$BASE_TAG" ] && continue
  printf '%s\n' "$PUBLISHED" | grep -qx "$t" || SKIPPED="$SKIPPED $t"
done
if [ -n "$SKIPPED" ]; then
  echo "  ⚠ неопубликованные теги в этом диапазоне:$SKIPPED"
  echo "    их изменения входят в черновик ниже — заметки обязаны рассказать и о них"
fi

echo
git -c core.abbrev=10 log "$RANGE" --no-merges --pretty=format:'%h%x09%s' -- "${OURS[@]}" \
  | python3 -c '
# -*- coding: utf-8 -*-
import re, sys, collections

TITLES = [
    ("feat",     "Новое"),
    ("fix",      "Исправлено"),
    ("perf",     "Быстрее"),
    ("refactor", "Переработано"),
    ("docs",     "Документация"),
    ("test",     "Тесты"),
    ("build",    "Сборка"),
    ("ci",       "CI"),
    ("chore",    "Прочее"),
]
groups = collections.defaultdict(list)
other, total = [], 0
for line in sys.stdin:
    line = line.rstrip("\n")
    if not line:
        continue
    sha, _, subject = line.partition("\t")
    total += 1
    m = re.match(r"^([a-z]+)(\([^)]*\))?!?:\s*(.+)$", subject)
    if m and any(m.group(1) == k for k, _ in TITLES):
        groups[m.group(1)].append((sha, (m.group(2) or "").strip("()"), m.group(3)))
    else:
        other.append((sha, "", subject))

if not total:
    print("Изменений в нашем коде с базлайна нет.")
    raise SystemExit(0)

for key, title in TITLES:
    if not groups[key]:
        continue
    print("## %s\n" % title)
    for sha, scope, text in groups[key]:
        print("- %s%s (`%s`)" % (("**%s:** " % scope) if scope else "", text, sha))
    print()
if other:
    print("## Без префикса — разобрать руками\n")
    for sha, _, text in other:
        print("- %s (`%s`)" % (text, sha))
    print()
print("_Черновик по %d коммитам нашего кода. Это сырьё, а не заметки: заметки пишутся о том, что\nчеловек почувствует, и по образцу из docs/vibe/references/._" % total)
'
