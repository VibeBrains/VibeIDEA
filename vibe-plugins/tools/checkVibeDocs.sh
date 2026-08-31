#!/usr/bin/env bash
# Гейт документации: записи, которых нет в индексе, не существует.
#
# Правило проекта простое — «добавил файл в knowledge → добавь строку в индекс», «новый мануал →
# строкой в дерево docs/vibe/README.md». Правило без проверки живёт до первой спешки, а находят
# такую пропажу через месяцы: файл лежит, его никто не читает, и вопрос решается второй раз с нуля.
#
# Четыре проверки, каждая падает на реальном нарушении:
#   1. запись базы знаний без строки в индексе knowledge/README.md;
#   2. строка индекса, ведущая на несуществующий файл;
#   3. мануал, не упомянутый в дереве docs/vibe/README.md;
#   4. битая относительная ссылка на .md внутри docs/vibe.
set -euo pipefail
cd "$(dirname "$0")/../.."

DOCS=docs/vibe
fail=0
say() { printf '%s\n' "$1"; }

# --- 1 и 2: база знаний против своего индекса ---
python3 - "$DOCS" <<'PY' || fail=1
import io, os, re, sys
docs = sys.argv[1]
index_path = os.path.join(docs, 'knowledge', 'README.md')
index = io.open(index_path, encoding='utf-8').read()
linked = set(re.findall(r'\]\(([^)]+\.md)\)', index))
ok = True

on_disk = []
for root, _, files in os.walk(os.path.join(docs, 'knowledge')):
    for name in files:
        if not name.endswith('.md') or name == 'README.md':
            continue
        rel = os.path.relpath(os.path.join(root, name), os.path.join(docs, 'knowledge'))
        on_disk.append(rel)

missing = [rel for rel in sorted(on_disk) if rel not in linked]
if missing:
    ok = False
    print("✖ записи базы знаний без строки в индексе (их никто не найдёт):")
    for rel in missing:
        print("   ", rel)

for target in sorted(linked):
    if target.startswith('http'):
        continue
    if not os.path.isfile(os.path.join(docs, 'knowledge', target)):
        ok = False
        print(f"✖ индекс базы знаний ссылается на несуществующий файл: {target}")

if ok:
    print(f"  база знаний: {len(on_disk)} записей, все в индексе")
sys.exit(0 if ok else 1)
PY

# --- 3: мануалы против дерева ---
python3 - "$DOCS" <<'PY' || fail=1
import io, os, sys
docs = sys.argv[1]
tree = io.open(os.path.join(docs, 'README.md'), encoding='utf-8').read()
manuals = sorted(f for f in os.listdir(os.path.join(docs, 'manuals')) if f.endswith('.md'))
missing = [m for m in manuals if f"manuals/{m}" not in tree]
if missing:
    print("✖ мануалы, не упомянутые в дереве docs/vibe/README.md:")
    for m in missing:
        print("   ", m)
    sys.exit(1)
print(f"  мануалы: {len(manuals)}, все в дереве")
PY

# --- 4: относительные ссылки внутри docs/vibe ---
python3 - "$DOCS" <<'PY' || fail=1
import io, os, re, sys
docs = sys.argv[1]
broken = []
for root, _, files in os.walk(docs):
    for name in files:
        if not name.endswith('.md'):
            continue
        path = os.path.join(root, name)
        text = io.open(path, encoding='utf-8').read()
        for target in re.findall(r'\]\(([^)]+\.md)(?:#[^)]*)?\)', text):
            if target.startswith(('http', '/')):
                continue
            resolved = os.path.normpath(os.path.join(root, target.split('#')[0]))
            if not os.path.isfile(resolved):
                broken.append(f"{os.path.relpath(path)} → {target}")
if broken:
    print("✖ битые ссылки на .md:")
    for b in broken[:20]:
        print("   ", b)
    sys.exit(1)
print("  ссылки внутри docs/vibe: битых нет")
PY

if [ "$fail" -ne 0 ]; then
  say "Гейт документации: ПРОВАЛЕН"
  exit 1
fi
say "Гейт документации: индекс, дерево и ссылки в порядке"
