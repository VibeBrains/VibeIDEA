#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Читаемость наших схем редактора: контраст каждого цвета текста к фону.

Зачем это гейт, а не вкус. «Выглядит плохо» бывает вкусом, а бывает измеримым: цвет комментария
к фону — это число, и ниже определённого значения текст перестаёт читаться у всех, а не у
привередливых. Замер 19.09.2026 нашёл ровно такой случай — комментарии полуночной темы шли на
2.83 при полу читаемости в 3, графитовой на 3.49. Глазами это видно как «тускло», числом — как
дефект.

Формула контраста — WCAG 2.1 (относительная яркость с гамма-коррекцией). Планка ниже той, что
стандарт просит для основного текста (4.5): подсветка синтаксиса намеренно делает часть токенов
тусклее, и требовать от комментария того же, что от кнопки, значило бы сломать саму идею
подсветки. Но ниже 4 не опускается ничто — это и есть граница между «тусклее» и «не прочесть».

Планка выбрана ПО ИЗМЕРЕНИЮ, а не из головы: после починки худшее значение по всем схемам —
4.14, и 4.0 стоит под ним, оставляя место обычному подбору цвета и ловя провалы.

Меряется ДВА набора: цвета схемы редактора и цвета нашего интерфейса (токены `Vibe.*`). Второй
добавлен 20.09.2026 и на первом же прогоне нашёл двенадцать провалов — тусклый текст ленты и
композера шёл на 2.08–3.53, потому что один и тот же `AccentDim` служил и украшением (подчёркивание,
рамка фокуса, рёбра графа), и ТЕКСТОМ. Украшению тусклость идёт, тексту она означает «не прочесть».

Вызов:  ./vibe-plugins/tools/themeContrast.py           — проверить (код возврата 1 при провале)
        ./vibe-plugins/tools/themeContrast.py --report  — напечатать все значения
"""
import glob
import json
import io
import os
import sys
import xml.etree.ElementTree as ET

FLOOR = 4.0
SCHEMES = 'vibe-plugins/vibe-theme/resources/*Scheme.xml'
THEMES = 'vibe-plugins/vibe-theme/resources/vibe*.theme.json'

# Пары «текст на фоне» нашего интерфейса. Таблица заземлённая, а не придуманная: либо тема сама
# называет обе половины (`terminalForeground` и `terminalBackground`), либо фон подтверждён кодом —
# `AgentPanel.CHAT_BG` = `Vibe.Chat.background`, `ComposerPanel.BG` = `Vibe.Composer.background`.
# Токен, чей фон не удалось подтвердить, в таблицу не попадает: мерить к выдуманному фону — хуже,
# чем не мерить.
UI_PAIRS = (
    ('Chat.terminalForeground', 'Chat.terminalBackground'),
    ('Chat.terminalOk', 'Chat.terminalBackground'),
    ('Chat.terminalError', 'Chat.terminalBackground'),
    ('Chat.metaForeground', 'Chat.background'),
    ('Composer.chipForeground', 'Composer.chipBackground'),
    ('Composer.queueForeground', 'Composer.queueBackground'),
    ('Composer.accentForeground', 'Composer.accent'),
    ('Composer.pillForeground', 'Composer.background'),
    ('Composer.usageForeground', 'Composer.background'),
    ('Composer.usageWarnForeground', 'Composer.background'),
    ('Tabs.activeForeground', 'Tabs.activeBackground'),
)
# Не цвета текста: подчёркивание, полоса ошибок и заливки меряются к другому и по другим правилам.
NOT_TEXT = {'FOREGROUND', 'BACKGROUND', 'EFFECT_COLOR', 'ERROR_STRIPE_COLOR'}


def luminance(value):
    v = value.strip().lstrip('#')
    if len(v) == 3:
        v = ''.join(c * 2 for c in v)
    if len(v) != 6:
        return None
    channels = []
    for i in (0, 2, 4):
        try:
            c = int(v[i:i + 2], 16) / 255
        except ValueError:
            return None
        channels.append(c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(foreground, background):
    a, b = luminance(foreground), luminance(background)
    if a is None or b is None:
        return None
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def text_background(root):
    for option in root.iter('option'):
        if option.get('name') == 'TEXT':
            for value in option.iter('option'):
                if value.get('name') == 'BACKGROUND':
                    return value.get('value')
    return None


def measure(path):
    """Пары «цвет текста → контраст» для одной схемы, от худшей к лучшей."""
    root = ET.parse(path).getroot()
    background = text_background(root)
    if not background:
        return None, []
    found = []
    for option in root.iter('option'):
        name = option.get('name')
        if not name or name in NOT_TEXT:
            continue
        for value in option.iter('option'):
            if value.get('name') == 'FOREGROUND' and value.get('value'):
                measured = contrast(value.get('value'), background)
                if measured:
                    found.append((measured, name, value.get('value')))
    found.sort()
    return background, found


def token(theme, path):
    """Значение токена `Vibe.<path>` с разрешённым именем палитры."""
    node = theme['ui']['Vibe']
    for part in path.split('.'):
        node = node[part]
    return theme['colors'].get(node, node)


def measure_ui(path):
    """Пары «токен → контраст» одной темы, от худшей к лучшей."""
    theme = json.loads(io.open(path, encoding='utf-8').read())
    found = []
    for foreground, background in UI_PAIRS:
        measured = contrast(token(theme, foreground), token(theme, background))
        if measured:
            found.append((measured, foreground, background))
    found.sort()
    return found


def main():
    report = '--report' in sys.argv
    failures = []
    for path in sorted(glob.glob(SCHEMES)):
        background, found = measure(path)
        name = os.path.basename(path)
        if background is None:
            failures.append('%s: не найден фон TEXT — мерить не к чему' % name)
            continue
        if report:
            print('%-28s фон %s' % (name, background))
            for measured, token, colour in found:
                print('    %6.2f  %-34s %s' % (measured, token, colour))
        for measured, token, colour in found:
            if measured < FLOOR:
                failures.append('%s: %s (%s) — контраст %.2f при планке %.2f'
                                % (name, token, colour, measured, FLOOR))
    for path in sorted(glob.glob(THEMES)):
        name = os.path.basename(path)
        found = measure_ui(path)
        if report:
            print('%-28s интерфейс' % name)
            for measured, foreground, background in found:
                print('    %6.2f  %-30s на %s' % (measured, foreground, background))
        for measured, foreground, background in found:
            if measured < FLOOR:
                failures.append('%s: Vibe.%s на Vibe.%s — контраст %.2f при планке %.2f'
                                % (name, foreground, background, measured, FLOOR))
    if failures:
        print('✖ цвет текста не читается на своём фоне:')
        for line in failures:
            print('    ' + line)
        print('  Осветлите цвет, сохранив тон: ниже %.1f текст не читается ни у кого' % FLOOR)
        return 1
    print('  контраст: все цвета текста выше планки %.1f — и в схемах редактора, и в наших панелях (%d пар)'
          % (FLOOR, len(UI_PAIRS)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
