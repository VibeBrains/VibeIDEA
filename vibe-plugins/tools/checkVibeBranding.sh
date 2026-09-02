#!/usr/bin/env bash
# Гейт брендинга: чужое имя не должно приезжать в наши файлы.
#
# Форк живёт синками с апстримом, и после каждого в наши ресурсы может попасть «IntelliJ IDEA» или
# «JetBrains» — из скопированной строки, из шаблона, из подсказки. Глазами это не увидеть: наших
# файлов десятки, а слово выглядит уместно ровно до момента публикации.
#
# Проверяются ТОЛЬКО наши каталоги: vibe-plugins/ и vibeidea-customization/. Апстрим не трогаем —
# там эти слова законны и не наши (правило границы форка).
#
# Исключения живут в brandingAllowlist.txt с ПРИЧИНОЙ. Запись, которая больше ничего не находит,
# тоже валит гейт: устаревшее исключение — такой же долг, как забытое упоминание.
set -euo pipefail
cd "$(dirname "$0")/../.."
. vibe-plugins/tools/pythonBin.sh

ALLOWLIST=vibe-plugins/tools/brandingAllowlist.txt
# vibeDefaults — submodule общего набора сидов (VibeBrains): его брендинг живёт в своём репозитории.
# resources/help — КОПИЯ docs/vibe, кладётся скриптом syncHelp.sh; упоминания платформы там законны
# (доки объясняют форк) и проверять их надо в источнике, а не в копии, иначе одно и то же
# упоминание пришлось бы объяснять дважды.
ROOTS=(vibe-plugins vibeidea-customization)
PATTERN='IntelliJ IDEA|JetBrains'
fail=0

hits=$(grep -rInE "$PATTERN" "${ROOTS[@]}" 2>/dev/null | grep -v '/vibeDefaults/' | grep -v '/resources/help/' | grep -v '/tools/brandingAllowlist.txt' | grep -v '/tools/checkVibeBranding.sh' || true)

"$PYTHON" - "$ALLOWLIST" <<PY || fail=1
import sys, io
allow_path = sys.argv[1]
hits = """$hits""".strip().splitlines()

rules = []
for raw in io.open(allow_path, encoding='utf-8'):
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    parts = line.split('|', 2)
    if len(parts) != 3:
        print("✖ строка списка исключений не по формату <путь>|<подстрока>|<причина>:", line)
        sys.exit(1)
    rules.append({'path': parts[0], 'needle': parts[1], 'reason': parts[2], 'used': False})

unexpected = []
for hit in hits:
    if not hit:
        continue
    path = hit.split(':', 1)[0]
    matched = next((r for r in rules if r['path'] == path and r['needle'] in hit), None)
    if matched:
        matched['used'] = True
    else:
        unexpected.append(hit)

ok = True
if unexpected:
    ok = False
    print("✖ чужой брендинг в наших файлах (добавьте исключение с причиной или уберите упоминание):")
    for hit in unexpected[:20]:
        print("   ", hit.strip()[:160])
stale = [r for r in rules if not r['used']]
if stale:
    ok = False
    print("✖ исключения, которые больше ничего не находят — удалите их:")
    for r in stale:
        print("    %s | %s" % (r['path'], r['needle']))
if ok:
    print("  упоминаний: %d, все объяснены в списке исключений (%d записей)" % (len(hits), len(rules)))
sys.exit(0 if ok else 1)
PY

if [ "$fail" -ne 0 ]; then
  echo "Гейт брендинга: ПРОВАЛЕН"
  exit 1
fi
echo "Гейт брендинга: чужого имени в наших файлах нет"
