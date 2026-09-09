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
import json

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


# ─── Обратная сторона: ключ в СИДЕ, которого не читает ни один парсер ────────────────────────
#
# Первая проверка идёт от кода: «поле разбирается и не применяется». Она по построению слепа к
# зеркальному случаю — ключ ЕСТЬ в файле-образце, который мы кладём человеку в `.vibe`, и его не
# разбирает никто. Снаружи это неотличимо ровно так же: человек копирует образец, IDE молчит,
# настройка не работает.
#
# Повод — 09.09.2026: набор сидов общий с VibeIDE (submodule VibeBrains), VibeIDE переименовала
# поля цены в `cost*`, и у НАС цена, срок годности и цена «после» разом перестали читаться. Первая
# проверка этого не увидела: с её стороны ничего не изменилось.

SEEDS = sorted(glob.glob('vibe-plugins/vibe-agent/resources/vibeDefaults/**/*.jsonc', recursive=True))
ALLOWLIST = 'vibe-plugins/tools/configFieldsAllowlist.txt'

# Карты со СВОБОДНЫМИ ключами: их имена придумывает пользователь, а не контракт. Заглядывать в них
# бессмысленно — там не настройки, а его собственные переменные, заголовки и поля тела запроса.
FREE_FORM = {'env', 'headers', 'query', 'extraBody'}

# Файлы, свободные целиком: имя окружения и имя переменной в нём выбирает человек.
FREE_FORM_FILES = {'httpClientEnv.example.jsonc'}


def strip_jsonc(text):
    """JSONC → JSON: убрать комментарии вне строк и висячие запятые."""
    out, i, n = [], 0, len(text)
    in_str = esc = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == '\\':
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            i += 2
            while i + 1 < n and not (text[i] == '*' and text[i + 1] == '/'):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return re.sub(r',(\s*[}\]])', r'\1', ''.join(out))


def seed_keys(node, parent, acc):
    if isinstance(node, dict):
        for k, v in node.items():
            acc.add(k)
            if k not in FREE_FORM:
                seed_keys(v, k, acc)
    elif isinstance(node, list):
        for v in node:
            seed_keys(v, parent, acc)


def read_allowlist():
    allowed = {}
    if not glob.glob(ALLOWLIST):
        return allowed
    for raw in io.open(ALLOWLIST, encoding='utf-8'):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        key, _, reason = line.partition('|')
        allowed[key.strip()] = reason.strip()
    return allowed


def check_seeds():
    sources = ''.join(io.open(p, encoding='utf-8').read() for p in SOURCES)
    allowed = read_allowlist()
    used = set()
    orphans = {}
    seen = set()
    for path in SEEDS:
        if path.split('/')[-1] in FREE_FORM_FILES:
            continue
        raw = io.open(path, encoding='utf-8').read()
        try:
            data = json.loads(strip_jsonc(raw))
        except ValueError as exc:
            print('ОШИБКА: %s: сид не разбирается как JSONC (%s)' % (path, exc))
            return 1, 0
        keys = set()
        seed_keys(data, None, keys)
        seen |= keys
        for key in sorted(keys):
            if key in allowed:
                used.add(key)
                continue
            # Читателем считается любое упоминание ключа строковым литералом: `o["x"]`,
            # `str("x")`, `text(o, "x", …)` — форма разбора у каждого плагина своя.
            if '"%s"' % key in sources:
                continue
            orphans.setdefault(key, []).append(path)

    problems = 0
    for key, files in sorted(orphans.items()):
        problems += 1
        print('ОШИБКА: ключ «%s» есть в сиде (%s) и не читается ни одним парсером'
              % (key, ', '.join(f.split('/')[-1] for f in files)))
        print('        Человек скопирует образец, IDE промолчит, настройка работать не будет.')
        print('        Прочитайте его, уберите из сида или назовите границей в %s.' % ALLOWLIST)
    for key, reason in sorted(allowed.items()):
        if key not in used:
            problems += 1
            print('ОШИБКА: исключение «%s» в %s больше ничего не находит' % (key, ALLOWLIST))
            print('        Причина была: %s' % reason)
            print('        Устаревшее исключение прячет следующий такой же ключ — удалите строку.')
    return problems, len(seen)


if __name__ == '__main__':
    code = main()
    seed_problems, seed_checked = check_seeds()
    print('  ключи сидов: проверено %d, осиротевших %d' % (seed_checked, seed_problems))
    sys.exit(1 if (code or seed_problems) else 0)
