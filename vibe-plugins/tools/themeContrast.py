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

Вызов:  ./vibe-plugins/tools/themeContrast.py           — проверить (код возврата 1 при провале)
        ./vibe-plugins/tools/themeContrast.py --report  — напечатать все значения
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

FLOOR = 4.0
SCHEMES = 'vibe-plugins/vibe-theme/resources/*Scheme.xml'
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
    if failures:
        print('✖ цвет текста в схеме редактора не читается на её же фоне:')
        for line in failures:
            print('    ' + line)
        print('  Осветлите цвет, сохранив тон: ниже %.1f текст не читается ни у кого' % FLOOR)
        return 1
    print('  контраст схем: все цвета текста выше планки %.1f' % FLOOR)
    return 0


if __name__ == '__main__':
    sys.exit(main())
