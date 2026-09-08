# -*- coding: utf-8 -*-
"""Гейт мёртвых полей конфигов: поле читается из файла пользователя и ничего не делает.

Повод — два случая за один день 08.09.2026, в разных плагинах и с разницей в годы написания:
`maxTokens`/`maxSteps` шага пайплайна и `autoStart` записи дев-стека. Оба разбирались, оба были
описаны в спеке, оба не делали ничего. Это худший вид отсутствующей возможности: человек написал
настройку, IDE её прочитала, не пожаловалась — и снаружи это неотличимо от работающей.

Что считается нарушением: свойство data class, которое (а) читается из JSON в том же файле по
одноимённому ключу — то есть приходит из файла ПОЛЬЗОВАТЕЛЯ, — и (б) нигде, кроме объявления и
самого разбора, не упоминается. Слияние (`over.x ?: base.x`) и копирование разбором не считаются:
это перекладывание значения, а не его применение.

Чего гейт НЕ ловит и не должен: поле, применённое неправильно. Он отвечает на вопрос «есть ли у
настройки потребитель», а не «верен ли он», — второе проверяют тесты.
"""
import io
import re
import sys
import glob

SOURCES = sorted(glob.glob('vibe-plugins/*/src/**/*.kt', recursive=True))
TESTS = sorted(glob.glob('vibe-plugins/*/testSrc/**/*.kt', recursive=True))

PROPERTY = re.compile(r'^\s+(?:val|var) (\w+)\s*:', re.M)
JSON_KEY = re.compile(r'\["([A-Za-z]\w*)"\]')


def is_plumbing(line, name):
    """Строка только перекладывает значение: объявление, разбор, слияние, копия."""
    stripped = line.strip()
    if re.match(r'^(val|var) %s\s*:' % re.escape(name), stripped):
        return True
    # `name = ...` в разборе, слиянии и copy(): значение переносится, но не применяется.
    if re.match(r'^%s\s*=' % re.escape(name), stripped):
        return True
    return False


def main():
    texts = {p: io.open(p, encoding='utf-8').read() for p in SOURCES + TESTS}
    problems = []
    checked = 0
    for path in SOURCES:
        text = texts[path]
        if 'data class' not in text:
            continue
        keys = set(JSON_KEY.findall(text))
        if not keys:
            continue
        for name in sorted(set(PROPERTY.findall(text))):
            if name not in keys:
                continue
            checked += 1
            word = re.compile(r'\b%s\b' % re.escape(name))
            uses = 0
            for other, body in texts.items():
                for line in body.split('\n'):
                    if not word.search(line):
                        continue
                    # Комментарий — не потребитель: поле, о котором только рассказано, не работает.
                    if line.lstrip().startswith(('//', '*', '/*')):
                        continue
                    if other == path and is_plumbing(line, name):
                        continue
                    uses += 1
            if uses == 0:
                problems.append((path, name))

    for path, name in problems:
        print('ОШИБКА: %s: поле «%s» читается из файла пользователя и нигде не применяется' % (path, name))
        print('        Настройка, которую IDE читает и молча игнорирует, неотличима от работающей.')
        print('        Примените его, откажитесь от него в разборе или удалите из контракта.')
    print('  поля конфигов: проверено %d, мёртвых %d' % (checked, len(problems)))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
