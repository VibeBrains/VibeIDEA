#!/usr/bin/env bash
# Гейт локализации VibeIDEA.
#
# Без гейта локализация протухает с первой новой кнопкой: строку добавили инлайном, каталог не
# тронули, и через месяц половина интерфейса не переводится. Четыре проверки:
#
#   1. ключ, использованный в коде, обязан быть в базовом каталоге;
#   2. мёртвый ключ (есть в каталоге, нигде не используется) — тоже ошибка: он врёт переводчику;
#   3. ключ в языковом файле, которого нет в базе — опечатка или устаревший перевод;
#   4. ХРАПОВИК: число русских литералов в UI-коде не может вырасти против записанного.
#
# Диагностика выведена из-под гейта явно: логи и console-вывод — не интерфейс, их переводить незачем.
set -euo pipefail
cd "$(dirname "$0")/../.."

BASE=vibe-plugins/vibe-agent/resources/lang/base.json
LANG_DIR=vibe-plugins/vibe-agent/resources/lang
SRC=vibe-plugins/vibe-agent/src
RATCHET_FILE=vibe-plugins/tools/i18nRatchet.txt
fail=0

say() { printf '%s\n' "$1"; }

[ -f "$BASE" ] || { say "✖ нет базового каталога строк: $BASE"; exit 1; }

# --- 1 и 2: ключи кода против каталога ---
python3 - "$BASE" "$SRC" <<'PY' || fail=1
import json, io, re, sys, os
base_path, src = sys.argv[1], sys.argv[2]
base = json.load(io.open(base_path, encoding='utf-8'))
used = set()
key_re = re.compile(r'\bt\(\s*"([a-zA-Z0-9_.-]+)"')
for root, _, files in os.walk(src):
    for name in files:
        if not name.endswith('.kt'):
            continue
        text = io.open(os.path.join(root, name), encoding='utf-8').read()
        used.update(key_re.findall(text))

missing = sorted(used - set(base))
dead = sorted(set(base) - used)
ok = True
if missing:
    ok = False
    print("✖ ключи используются в коде, но их нет в базовом каталоге:")
    for key in missing: print("   ", key)
if dead:
    ok = False
    print("✖ мёртвые ключи в базовом каталоге (нигде не используются):")
    for key in dead: print("   ", key)
if ok:
    print(f"  каталог: {len(base)} ключей, все используются")
sys.exit(0 if ok else 1)
PY

# --- 3: языковые файлы против базы ---
python3 - "$BASE" "$LANG_DIR" <<'PY' || fail=1
import json, io, sys, os, glob
base_path, lang_dir = sys.argv[1], sys.argv[2]
base = set(json.load(io.open(base_path, encoding='utf-8')))
ok = True
for path in sorted(glob.glob(os.path.join(lang_dir, '*.json'))):
    if os.path.basename(path) == 'base.json':
        continue
    data = json.load(io.open(path, encoding='utf-8'))
    unknown = sorted(set(data) - base)
    if unknown:
        ok = False
        print(f"✖ {os.path.basename(path)}: ключи, которых нет в базе: {', '.join(unknown)}")
    else:
        missing = len(base - set(data))
        note = f", без перевода {missing}" if missing else ", перевод полный"
        print(f"  {os.path.basename(path)}: {len(data)} ключей{note}")
sys.exit(0 if ok else 1)
PY

# --- 4: храповик по ИНТЕРФЕЙСНЫМ русским литералам ---
#
# Считаются только строки, которые человек может увидеть. Файлы, где русский текст — это ДАННЫЕ
# (регэкспы детекторов, преамбулы ролей в промпте), перечислены в i18nExclusions.txt с причиной:
# переводить их не только не нужно, но и вредно — детектор ищет русские слова в чужой странице.
EXCLUSIONS=vibe-plugins/tools/i18nExclusions.txt
excluded_paths=$(grep -v '^#' "$EXCLUSIONS" 2>/dev/null | grep -v '^$' | cut -d'|' -f1 || true)
count=$(find vibe-plugins -name '*.kt' -path '*/src/*' | { while read -r f; do
    skip=0
    for ex in $excluded_paths; do [ "$f" = "$ex" ] && skip=1 && break; done
    [ "$skip" -eq 0 ] && printf '%s\n' "$f"
  done; } | xargs grep -o '"[^"]*[А-Яа-яЁё][^"]*"' 2>/dev/null | wc -l | tr -d ' ')
# Планка обязана существовать. Раньше её отсутствие подставляло текущее число — и храповик
# молча пропускал любой рост, то есть был гейтом-пустышкой. Гейт, который нельзя провалить,
# не защищает ничего.
if [ ! -f "$RATCHET_FILE" ]; then
  say "✖ нет файла планки $RATCHET_FILE — создайте его с текущим числом: echo $count > $RATCHET_FILE"
  exit 1
fi
limit=$(cat "$RATCHET_FILE")
if [ "$count" -gt "$limit" ]; then
  say "✖ русских литералов в коде: $count, разрешено не больше $limit."
  say "  Новая строка интерфейса должна идти через каталог: t(\"ключ\") + запись в $BASE."
  say "  Строка НЕ для человека (регэксп детектора, преамбула промпта) — файл в $EXCLUSIONS с причиной."
  say "  Если строка НЕ интерфейсная (лог, исключение для разработчика) — так и есть, но храповик"
  say "  считает по коду целиком: перенесите столько же строк из очереди, чтобы счётчик не рос."
  fail=1
elif [ "$count" -lt "$limit" ]; then
  say "  храповик: литералов $count (было $limit) — опускаю планку"
  printf '%s\n' "$count" > "$RATCHET_FILE"
else
  say "  храповик: литералов $count, планка $limit"
fi

if [ "$fail" -ne 0 ]; then
  say "Гейт локализации: ПРОВАЛЕН"
  exit 1
fi
say "Гейт локализации: каталог, языки и храповик в порядке"
