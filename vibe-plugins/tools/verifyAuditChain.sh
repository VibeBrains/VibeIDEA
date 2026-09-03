#!/usr/bin/env bash
# Независимая проверка цепочки журнала аудита — без IDE.
#
# Зачем отдельным скриптом: доказательство, которое умеет проверить только та программа, что его и
# написала, доказывает мало. Журнал выгружают, чтобы показать другому человеку — коллеге, разбору
# инцидента, — и у него должен быть способ проверить файл своими руками. Алгоритм полностью описан
# в docs/vibe/manuals/auditSpec.md: тридцать строк на любом языке.
#
# Использование: ./vibe-plugins/tools/verifyAuditChain.sh <файл.jsonl>
set -euo pipefail
. "$(dirname "$0")/pythonBin.sh"

FILE="${1:-}"
[ -n "$FILE" ] || { echo "✖ укажите файл: verifyAuditChain.sh <audit.jsonl>"; exit 1; }
[ -f "$FILE" ] || { echo "✖ файла нет: $FILE"; exit 1; }

"$PYTHON" - "$FILE" <<'PYEOF'
# -*- coding: utf-8 -*-
import hashlib, io, sys

FIELD = '"h":"'
GENESIS = "0"
LINK_LENGTH = 12

path = sys.argv[1]
previous = GENESIS
checked = 0
linked = 0
unlinked = None

with io.open(path, encoding="utf-8") as handle:
    for number, line in enumerate(handle, start=1):
        line = line.rstrip("\n")
        if not line.strip():
            continue
        checked += 1
        marker = ',' + FIELD
        at = line.rfind(marker)
        if at < 0:
            # Запись старого формата: она не подделка, ей просто нечем подтвердиться.
            if unlinked is None:
                unlinked = number
            payload = line
            previous = hashlib.sha256((previous + payload).encode("utf-8")).hexdigest()[:LINK_LENGTH]
            continue
        payload = line[:at] + "}"
        carried = line[at + len(marker):].split('"', 1)[0]
        expected = hashlib.sha256((previous + payload).encode("utf-8")).hexdigest()[:LINK_LENGTH]
        if carried != expected:
            print("✖ цепочка разорвана на строке %d: записано %s, ожидалось %s" % (number, carried, expected))
            print("  Записи начиная с этой строки изменены после того, как были написаны.")
            sys.exit(1)
        previous = carried
        linked += 1

# Формулировка — не украшение: «журнал не правили» на файле, где проверять было нечего, это
# ложное утверждение, а разбор инцидента читает именно последнюю строку.
if checked == 0:
    print("Записей нет — проверять нечего.")
elif linked == 0:
    print("✖ Ни одна запись не несёт звена: это журнал, написанный до появления цепочки.")
    print("  Подтвердить его целостность нечем — и это НЕ то же самое, что «не правили».")
    sys.exit(2)
elif unlinked is not None:
    print("Цепочка сходится на %d записях; записи до строки %d писались до её появления — подтвердить их нечем."
          % (linked, unlinked))
else:
    print("Цепочка сходится на %d записях: журнал не правили." % checked)
PYEOF
