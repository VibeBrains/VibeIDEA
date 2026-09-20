#!/usr/bin/env bash
# Мёртвые импорты в наших исходниках.
#
# Зачем гейт, а не «почистим когда-нибудь». Kotlin на неиспользуемый импорт не ругается, поэтому он
# и накапливается молча: замер 20.09.2026 нашёл 67 мёртвых импортов в 42 файлах — по всем пяти нашим
# плагинам сразу. Каждый из них это ложный след: читающий файл видит зависимость, которой нет, а
# поиск «кто пользуется этим классом» отвечает именами, которые им не пользуются.
#
# Проверяется ВЫЗОВ по имени, а не сам факт импорта: имя, встреченное в файле хоть раз помимо своей
# строки импорта (включая KDoc-ссылку `[Имя]`), считается живым — гейт, ошибающийся в сторону
# «оставить», безопаснее гейта, требующего удалить нужное.
set -u
root=$(cd "$(dirname "$0")/../.." && pwd)
PYTHON=${PYTHON:-python3}
cd "$root" || exit 1

"$PYTHON" - "$root" <<'PYIMPORTS'
# -*- coding: utf-8 -*-
import glob, io, os, re, sys
root = sys.argv[1]

# Имена, которые работают БЕЗ упоминания: операторные соглашения и делегаты зовутся синтаксисом
# (`a + b`, `by`, `f()`), а не по имени, и «не встречается в тексте» для них ничего не значит.
BY_SYNTAX = {
    'invoke', 'plus', 'minus', 'times', 'div', 'rem', 'unaryMinus', 'unaryPlus', 'inc', 'dec',
    'get', 'set', 'contains', 'compareTo', 'equals', 'hashCode', 'iterator', 'rangeTo',
    'getValue', 'setValue', 'provideDelegate', 'plusAssign', 'minusAssign',
}
BY_SYNTAX |= {'component%d' % i for i in range(1, 10)}

dead = []
files = sorted(glob.glob(os.path.join(root, 'vibe-plugins/*/src/**/*.kt'), recursive=True) +
               glob.glob(os.path.join(root, 'vibe-plugins/*/testSrc/**/*.kt'), recursive=True))
for path in files:
    text = io.open(path, encoding='utf-8').read()
    for name in re.findall(r'^import (?:[\w.]+\.)?(\w+)$', text, re.M):
        if name in BY_SYNTAX:
            continue
        uses = len(re.findall(r'\b%s\b' % re.escape(name), text)) \
             - len(re.findall(r'^import .*\b%s$' % re.escape(name), text, re.M))
        if uses == 0:
            dead.append('%s: %s' % (os.path.relpath(path, root), name))
if dead:
    print('ОШИБКА: импорт есть, а пользуется им никто — ложный след для читающего:')
    for line in dead:
        print('    ' + line)
    print('  Уберите строку импорта; если имя нужно только в KDoc — сошлитесь на него как [Имя]')
    sys.exit(1)
print('  импорты: мёртвых нет в %d файлах' % len(files))
PYIMPORTS
status=$?

if [ $status -eq 0 ]; then
  echo "Гейт импортов: ложных следов нет"
else
  echo "Гейт импортов: ПРОВАЛЕН"
fi
exit $status
