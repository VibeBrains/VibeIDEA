# -*- coding: utf-8 -*-
"""Девять цветов проекта для каждой темы (генератор; запускать из корня репозитория).

Вызов:  ./vibe-plugins/tools/themeProjectColors.py            — переписать цвета во всех темах
        ./vibe-plugins/tools/themeProjectColors.py --check    — только проверить, ничего не писать

Девять цветов проекта для каждой темы — оттенками САМОЙ темы, а не по всему кругу.

Платформа красит шапку окна цветом проекта (`RecentProject.ColorN.MainToolbarGradientStart`,
настройка «Use project colors in main toolbar» включена по умолчанию). Наши темы этих ключей не
объявляли — платформа брала свои: красный, оранжевый, бирюзовый, пурпурный, розовый. Владелец
увидел пурпурную полосу и на графитовой теме, и на полуночной: одинаковую, то есть чужую.

Первый заход развёл девять цветов по всему кругу и дал графиту зелёный — такой же чужой, как
пурпурный, только другой. Поэтому оттенки берутся УЗКОЙ вилкой вокруг акцента темы: проекты по-
прежнему различимы (ради этого настройка и существует), но ни один не выпадает из палитры.
"""
import colorsys, json, pathlib

THEMES = pathlib.Path('vibe-plugins/vibe-theme/resources')
KEY = 'RecentProject.Color%d.MainToolbarGradientStart'
COUNT = 9
HUE_SPAN = 0.14      # вся вилка ≈50° — различимо, но в одной семье
SATURATION = 0.34    # приглушённо: это фон шапки, а не вывеска
LIFT = 0.13          # тёмная тема: насколько светлее фона панели
DARK_CEILING = 0.42  # выше — полоса начинает спорить с тёмным интерфейсом
DROP = 0.13          # светлая тема: насколько ТЕМНЕЕ фона панели
LIGHT_FLOOR = 0.78   # ниже — на светлой теме получается тёмная плашка поперёк шапки


def rgb(value):
    v = value.lstrip('#')
    return tuple(int(v[i:i + 2], 16) / 255 for i in (0, 2, 4))


def hexed(parts):
    return '#%02X%02X%02X' % tuple(max(0, min(255, round(c * 255))) for c in parts)


def colors_for(accent, panel, dark):
    """Девять оттенков вокруг акцента темы, светлее её панели на тёмной теме и темнее — на светлой.

    Сторона темы здесь обязательна. Первая версия считала только для тёмных (других у нас не было)
    и зажимала светлоту сверху: на светлой теме та же формула дала бы тёмную плашку поперёк светлой
    шапки. Гейт этого не увидел бы — он сверяет генератор с им же посчитанными значениями.
    """
    ah, _, _ = colorsys.rgb_to_hls(*rgb(accent))
    pr, pg, pb = rgb(panel)
    _, pl, _ = colorsys.rgb_to_hls(pr, pg, pb)
    lightness = min(DARK_CEILING, pl + LIFT) if dark else max(LIGHT_FLOOR, pl - DROP)
    out = []
    for i in range(COUNT):
        hue = (ah + HUE_SPAN * (i / (COUNT - 1) - 0.5)) % 1.0
        out.append(hexed(colorsys.hls_to_rgb(hue, lightness, SATURATION)))
    return out


def run(check_only):
    drift = []
    for path in sorted(THEMES.glob('vibe*.theme.json')):
        data = json.loads(path.read_text(encoding='utf-8'))
        palette = data.get('colors', {})
        # Акцент обязателен: без него цвета проекта считались бы от чужого умолчания, и тема
        # получила бы чужую полосу в шапке (так и вышло у неоновой темы, 19.09.2026).
        accent = palette.get('Accent')
        panel = palette.get('PanelBg') or palette.get('DeepBg')
        if not accent or not panel:
            drift.append('%s: нет Accent или PanelBg в палитре' % path.name)
            continue
        ui = data.setdefault('ui', {})
        values = colors_for(accent, panel, bool(data.get('dark', True)))
        for index, value in enumerate(values, start=1):
            key = KEY % index
            if check_only:
                if ui.get(key) != value:
                    drift.append('%s: %s = %s, а должен быть %s' % (path.name, key, ui.get(key), value))
            else:
                ui[key] = value
        if not check_only:
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print('%-26s акцент %s → %s … %s' % (path.name, accent, values[0], values[-1]))
    return drift


if __name__ == '__main__':
    import sys
    check = '--check' in sys.argv
    problems = run(check)
    if problems:
        print('✖ цвета проекта разошлись с палитрой темы:')
        for line in problems:
            print('    ' + line)
        print('  Перегенерируйте: ./vibe-plugins/tools/themeProjectColors.py')
        sys.exit(1)
    if check:
        print('  цвета проекта: у всех тем совпадают с их палитрой')
