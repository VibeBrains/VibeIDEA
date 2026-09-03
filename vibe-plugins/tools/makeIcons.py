#!/usr/bin/env python3
"""Значки тулвиндоу VibeIDEA: одна геометрия — четыре файла.

Платформа требует у значка тулвиндоу четвёрку: 16×16 и 20×20, каждый в светлом и тёмном варианте,
и геометрия во всех обязана совпадать — иначе анимация выделения и HiDPI дают рывок. Держать четыре
файла руками значит однажды поправить три из четырёх, поэтому источник правды один: описание ниже,
а файлы генерируются.

Цвета — из канонической палитры New UI (#6C707E светлый, #CED0D6 тёмный). Неон нашей темы здесь
неуместен: полоса тулвиндоу монохромна по построению, и цветной значок в ней выглядит чужим и ломает
состояние выделения.

Запуск: python3 vibe-plugins/tools/makeIcons.py [--check]
  --check — не писать, а проверить, что файлы на диске совпадают с описанием (для гейта).
"""
import io
import os
import sys

LIGHT = "#6C707E"
DARK = "#CED0D6"
OUT = "vibe-plugins/vibe-agent/resources/icons"
HEADER = "<!-- Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license. -->"

# Каждый значок: имя → (тело 16×16, тело 20×20). {C} подставляется цветом.
# Метафоры выбраны так, чтобы различаться в полосе шириной 20 px: искра, щит, линейка, пульс,
# книга, часы. Одинаковый значок у шести панелей — это отсутствие значка.
ICONS = {
    # Агент: четырёхлучевая искра — она же в логотипе продукта.
    "vibeAgent": (
        '<path d="M8 2.2C8.5 5.7 9.6 6.9 13.3 8 9.6 9.1 8.5 10.3 8 13.8 7.5 10.3 6.4 9.1 2.7 8 6.4 6.9 7.5 5.7 8 2.2Z" fill="{C}"/>',
        '<path d="M10 2.8C10.6 7.1 12 8.6 16.6 10 12 11.4 10.6 12.9 10 17.2 9.4 12.9 8 11.4 3.4 10 8 8.6 9.4 7.1 10 2.8Z" fill="{C}"/>',
    ),
    # Аудит: щит с галочкой — журнал с цепочкой целостности, а не просто список.
    "vibeAudit": (
        '<path d="M8 1.8 13 3.5V8c0 3-2.1 5-5 6.2C5.1 13 3 11 3 8V3.5L8 1.8Z" stroke="{C}" stroke-width="1" stroke-linejoin="round"/>'
        '<path d="M5.8 7.9 7.4 9.5 10.4 6.2" stroke="{C}" stroke-width="1" stroke-linecap="round" stroke-linejoin="round"/>',
        '<path d="M10 2.3 16.5 4.5v5.8c0 3.8-2.7 6.4-6.5 7.9-3.8-1.5-6.5-4.1-6.5-7.9V4.5L10 2.3Z" stroke="{C}" stroke-width="1.4" stroke-linejoin="round"/>'
        '<path d="M7.2 10.1 9.3 12.2 13.1 8" stroke="{C}" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>',
    ),
    # Дизайн: линейка с делениями — детектор меряет страницу, а не рисует её.
    "vibeDesign": (
        '<rect x="1.9" y="5.6" width="12.2" height="4.8" rx="1" transform="rotate(-45 8 8)" stroke="{C}" stroke-width="1" stroke-linejoin="round"/>'
        '<path d="M6.1 6.9 7.2 8M8 5 9.1 6.1M4.2 8.8 5.3 9.9" stroke="{C}" stroke-width="1" stroke-linecap="round"/>',
        '<rect x="2.4" y="7" width="15.2" height="6" rx="1.4" transform="rotate(-45 10 10)" stroke="{C}" stroke-width="1.4" stroke-linejoin="round"/>'
        '<path d="M7.6 8.6 9 10M9.9 6.3 11.3 7.7M5.3 10.9 6.7 12.3" stroke="{C}" stroke-width="1.4" stroke-linecap="round"/>',
    ),
    # Диспетчерская: пульс — ходы идут или не идут, и это видно одним взглядом.
    "vibeRuns": (
        '<path d="M1.8 8h2.6l1.7-3.9 2.6 7.6 1.6-3.7h3.9" stroke="{C}" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>',
        '<path d="M2.4 10h3.3l2.1-4.9 3.2 9.5 2-4.6h4.6" stroke="{C}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>',
    ),
    # Документы: раскрытая книга — знания проекта, а не файл.
    "vibeDocs": (
        '<path d="M8 4.6C6.8 3.6 5.3 3.2 2.8 3.3v8.9c2.5-.1 4 .3 5.2 1.3 1.2-1 2.7-1.4 5.2-1.3V3.3c-2.5-.1-4 .3-5.2 1.3Z" stroke="{C}" stroke-width="1" stroke-linejoin="round"/>'
        '<path d="M8 4.6v9" stroke="{C}" stroke-width="1" stroke-linecap="round"/>',
        '<path d="M10 5.8C8.5 4.5 6.6 4 3.5 4.1v11.1c3.1-.1 5 .4 6.5 1.7 1.5-1.3 3.4-1.8 6.5-1.7V4.1c-3.1-.1-5 .4-6.5 1.7Z" stroke="{C}" stroke-width="1.4" stroke-linejoin="round"/>'
        '<path d="M10 5.8v11.1" stroke="{C}" stroke-width="1.4" stroke-linecap="round"/>',
    ),
    # Фоновые задачи: часы — у задачи есть срок жизни, и он объявлен.
    "vibeTasks": (
        '<circle cx="8" cy="8" r="6" stroke="{C}" stroke-width="1"/>'
        '<path d="M8 4.6V8l2.4 1.7" stroke="{C}" stroke-width="1" stroke-linecap="round" stroke-linejoin="round"/>',
        '<circle cx="10" cy="10" r="7.5" stroke="{C}" stroke-width="1.4"/>'
        '<path d="M10 5.7V10l3 2.1" stroke="{C}" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>',
    ),
}


def svg(size: int, body: str, colour: str) -> str:
    return (
        f'{HEADER}\n'
        f'<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}" fill="none" xmlns="http://www.w3.org/2000/svg">\n'
        f'{body.format(C=colour)}\n'
        f'</svg>\n'
    )


def files():
    for name, (body16, body20) in ICONS.items():
        yield f"{name}.svg", svg(16, body16, LIGHT)
        yield f"{name}_dark.svg", svg(16, body16, DARK)
        yield f"{name}@20x20.svg", svg(20, body20, LIGHT)
        yield f"{name}@20x20_dark.svg", svg(20, body20, DARK)


def main() -> int:
    check = "--check" in sys.argv
    problems = []
    for filename, content in files():
        path = os.path.join(OUT, filename)
        if check:
            if not os.path.isfile(path):
                problems.append(f"нет файла: {path}")
            elif io.open(path, encoding="utf-8").read() != content:
                problems.append(f"расходится с описанием: {path} (перегенерируйте makeIcons.py)")
        else:
            io.open(path, "w", encoding="utf-8").write(content)
    if check:
        for problem in problems:
            print("✖ " + problem)
        if not problems:
            print(f"  значки: {len(ICONS)} × 4 файла совпадают с описанием")
        return 1 if problems else 0
    print(f"  записано {len(ICONS) * 4} файлов в {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
