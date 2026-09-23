#!/usr/bin/env bash
# Гейт языка комментариев: храповик по комментариям с кириллицей в нашем коде.
#
# Правило проекта — комментарии в коде по-английски (docs/vibe/agentsGuide.md). Строки интерфейса
# держит гейт локализации, а комментарии не держал никто: за один день в них прибавилось больше
# трёхсот русских строк, и ни одна проверка этого не заметила.
#
# Старый долг здесь не переписывается разом — это тысячи строк и чужая история правок. Храповик
# делает другое: число не может вырасти, а когда падает — планка опускается сама. Долг только
# тает, и тает там, где код и так трогают.
#
# Что считается строкой комментария с кириллицей:
#   1. строка, которая НАЧИНАЕТСЯ с комментария (`//`, `/*`, `*`, `*/`) и содержит кириллицу;
#   2. хвостовой `// …` после кода, если в хвосте есть кириллица и нет кавычки — кавычка значит,
#      что `//` скорее всего стоит внутри строки (адрес в литерале), и такой хвост не считается.
# Имена тестов в обратных кавычках и строковые литералы сюда не входят: первые — принятое в
# проекте соглашение, вторые — забота гейта локализации.
#
# Где: vibe-plugins/*/src и testSrc (Kotlin и Java), плюс наши файлы сборки build/src/**/Vibe*.kt.
set -euo pipefail
cd "$(dirname "$0")/../.."

RATCHET_FILE=vibe-plugins/tools/commentsRatchet.txt
say() { printf '%s\n' "$1"; }

. vibe-plugins/tools/pythonBin.sh

count=$("$PYTHON" - <<'PY'
import re, glob
CYR = re.compile(r'[А-Яа-яЁё]')
LEADING = re.compile(r'^\s*(//|/\*|\*)')
# Only src/testSrc of each plugin: a recursive walk over vibe-plugins would descend into the extracted
# node_modules of the bundled servers — tens of thousands of files with nothing of ours in them.
paths = []
for root in ('src', 'testSrc'):
    for ext in ('kt', 'java'):
        paths += glob.glob(f'vibe-plugins/*/{root}/**/*.{ext}', recursive=True)
paths += glob.glob('build/src/**/Vibe*.kt', recursive=True)
n = 0
for path in paths:
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if LEADING.match(line):
                if CYR.search(line):
                    n += 1
                continue
            i = line.find('//')
            if i >= 0:
                tail = line[i:]
                if CYR.search(tail) and '"' not in tail:
                    n += 1
print(n)
PY
)

# Планка обязана существовать: подставлять текущее число при её отсутствии значит пропускать любой
# рост, то есть держать гейт, который нельзя провалить.
if [ ! -f "$RATCHET_FILE" ]; then
  say "✖ нет файла планки $RATCHET_FILE — создайте его с текущим числом: echo $count > $RATCHET_FILE"
  exit 1
fi
limit=$(cat "$RATCHET_FILE")

if [ "$count" -gt "$limit" ]; then
  say "✖ строк комментариев с кириллицей: $count, разрешено не больше $limit."
  say "  Комментарии в коде — по-английски (docs/vibe/agentsGuide.md): объясняют «почему так»,"
  say "  а не историю вопроса. Найти добавленное:"
  say "  git diff main -- '*.kt' '*.java' | grep -E '^[+][[:space:]]*(//|/[*]|[*])|^[+].*//' | grep -E '[А-Яа-яЁё]'"
  say "Гейт комментариев: ПРОВАЛЕН"
  exit 1
elif [ "$count" -lt "$limit" ]; then
  say "  храповик комментариев: $count (было $limit) — опускаю планку"
  printf '%s\n' "$count" > "$RATCHET_FILE"
else
  say "  храповик комментариев: $count, планка $limit"
fi
say "Гейт комментариев: язык комментариев не хуже, чем был"
