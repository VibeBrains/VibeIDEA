#!/usr/bin/env bash
# Гейт локализации VibeIDEA.
#
# Без гейта локализация протухает с первой новой кнопкой: строку добавили инлайном, каталог не
# тронули, и через месяц половина интерфейса не переводится. Четыре проверки:
#
#   1. ключ, использованный в коде, обязан быть в базовом каталоге;
#   2. мёртвый ключ (есть в каталоге, нигде не используется) — тоже ошибка: он врёт переводчику;
#   3. ключ в языковом файле, которого нет в базе — опечатка или устаревший перевод;
#   4. ХРАПОВИК: число русских литералов в коде не может вырасти против записанного (сейчас 0).
#
# Диагностика выведена из-под гейта явно: логи и console-вывод — не интерфейс, их переводить незачем.
set -euo pipefail
cd "$(dirname "$0")/../.."

BASE=vibe-plugins/vibe-agent/resources/lang/base.json
LANG_DIR=vibe-plugins/vibe-agent/resources/lang
# Каталог строк ОБЩИЙ для всех наших плагинов (решение владельца 2026-08-30): две копии одной
# кнопки «Отмена» однажды разойдутся в формулировке. Значит и использования ключей ищем во всех
# наших модулях, иначе строка, вызванная из vibe-server, считается мёртвой.
SRC=vibe-plugins
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
# Счёт ведёт python, а не grep: диапазон [А-Яа-яЁё] в POSIX-grep ловит по байтам и считает
# кириллицей типографские тире, точки-разделители и стрелки. Планка при этом «держалась» на
# пунктуации, а настоящая непереведённая строка тонула в шуме.
count=$(python3 - "$EXCLUSIONS" <<'PYCOUNT'
import io, os, re, sys
excluded = set()
for line in io.open(sys.argv[1], encoding='utf-8'):
    line = line.strip()
    if line and not line.startswith('#'):
        excluded.add(line.split('|', 1)[0].strip())
literal = re.compile(r'"(?:[^"\\]|\\.)*"')
cyrillic = re.compile(r'[\u0400-\u04FF]')
total = 0
for root, _, files in os.walk('vibe-plugins'):
    if os.sep + 'src' + os.sep not in root + os.sep:
        continue
    for name in files:
        if not name.endswith('.kt'):
            continue
        path = os.path.join(root, name)
        if path in excluded:
            continue
        text = io.open(path, encoding='utf-8').read()
        # Комментарии считать нельзя: они по правилам проекта английские, а редкая кириллица
        # внутри них — пример или цитата, а не строка интерфейса.
        text = re.sub(r'//[^\n]*', '', text)
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        total += sum(1 for m in literal.finditer(text) if cyrillic.search(m.group(0)))
print(total)
PYCOUNT
)
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
  say "  Лог IDE и комментарий — не интерфейс, и русскими быть не должны: пишите их по-английски."
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
