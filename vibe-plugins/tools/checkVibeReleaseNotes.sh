#!/usr/bin/env bash
# Гейт заметок к релизу: проверяет тело будущего GitHub Release ДО публикации.
#
# Механика перенята у VibeIDE (`scripts/vibe-release-lint.js`, 04.09.2026): там блок поддержки и
# QR проверяются линтом, а не памятью. Повод перенять — выпуск 0.4.0, в котором я молча пропустил
# весь шаг с фразой поддержки: заметки ушли без подписи, и заметил это владелец, а не процесс.
#
# Проверяется:
#   1. Имя файла несёт ту же версию, что выпускается.
#   2. Не осталось заготовок вида __SHA256__.
#   3. Блок «Поддержать проект» есть и стоит последним.
#   4. В нём постоянная вступительная строка, а СРАЗУ под ней — подпись, без пустой строки между
#      (пустая строка разбивает блок на два абзаца — правка владельца после v0.4.0).
#   5. Подпись записана в историю использования releaseDonationPhrases.md под этой версией:
#      история — гард от повтора, и запись в неё обязана существовать до публикации.
#   6. Ссылка на QR абсолютная (raw.githubusercontent.com): относительные пути работают в README
#      и не работают в теле релиза.
#
# Использование: ./vibe-plugins/tools/checkVibeReleaseNotes.sh vX.Y.Z <файл-заметок>
set -euo pipefail
cd "$(dirname "$0")/../.."

VERSION="${1:-}"
NOTES="${2:-}"
[ -n "$VERSION" ] && [ -n "$NOTES" ] || { echo "✖ использование: checkVibeReleaseNotes.sh vX.Y.Z <файл-заметок>"; exit 1; }
[ -f "$NOTES" ] || { echo "✖ нет файла заметок: $NOTES"; exit 1; }
PHRASES=docs/vibe/releaseDonationPhrases.md

python3 - "$VERSION" "$NOTES" "$PHRASES" <<'PY'
import io, re, sys
version, notes_path, phrases_path = sys.argv[1], sys.argv[2], sys.argv[3]
text = io.open(notes_path, encoding='utf-8').read()
lines = text.split('\n')
problems = []

bare = version.lstrip('v')
if bare not in notes_path:
    problems.append(f'имя файла заметок не несёт версию {bare}: {notes_path}')

for placeholder in ('__SHA256__', 'TODO', 'XXX'):
    if placeholder in text:
        problems.append(f'в заметках осталась заготовка {placeholder}')

INTRO = 'Если VibeIDEA оказался полезным — буду рад благодарности.'
heading = [i for i, line in enumerate(lines) if line.strip().lstrip('#').strip() == 'Поддержать проект']
if not heading:
    problems.append('нет блока «Поддержать проект» — фраза поддержки обязательна на каждом релизе')
else:
    start = heading[-1]
    after = [line for line in lines[start + 1:] if line.strip()]
    section_after = [line for line in lines[start + 1:] if line.startswith('## ')]
    if section_after:
        problems.append('после блока «Поддержать проект» идёт ещё раздел — блок обязан быть последним')
    intro_at = next((i for i in range(start, len(lines)) if lines[i].strip() == INTRO), None)
    if intro_at is None:
        problems.append('в блоке поддержки нет постоянной вступительной строки')
    else:
        signature = lines[intro_at + 1].strip() if intro_at + 1 < len(lines) else ''
        if not signature:
            problems.append('под вступительной строкой пусто: подпись обязана идти СРАЗУ, без пустой строки')
        elif signature.startswith('<'):
            problems.append('сразу под вступительной строкой разметка, а не подпись — фраза пропущена')
        else:
            history = io.open(phrases_path, encoding='utf-8').read()
            row = f'| {version} |'
            if row not in history:
                problems.append(f'подпись не записана в историю использования: нет строки «{row}» в {phrases_path}')
            elif signature not in history:
                problems.append('подпись в заметках не совпадает с записанной в истории использования')
    if 'raw.githubusercontent.com' not in '\n'.join(lines[start:]):
        problems.append('ссылка на QR не абсолютная: в теле релиза относительные пути не работают')

for problem in problems:
    print('✖ ' + problem)
if not problems:
    print(f'  заметки {version}: блок поддержки на месте, подпись записана в историю, QR абсолютный')
sys.exit(1 if problems else 0)
PY
