# -*- coding: utf-8 -*-
"""Раскладка окна установки: пишет `.DS_Store` на смонтированном томе образа.

Зачем она у нас своя, если сборка платформы это уже делает. Делает, но не выигрывает гонку:
том монтируется ВИДИМЫМ для Finder (`-noautoopen` скрывает только автооткрытие окна), и после
записи раскладки скрипт ещё несколько минут проверяет подпись и запускает лаунчер. За это время
Finder успевает переписать `.DS_Store` своими умолчаниями — иногда успевает, иногда нет. Наружу
это выходит так: один образ открывается оформленным окном, следующий, собранный тем же кодом, —
простым списком файлов (владелец, 0.6.8; у 0.6.0 файл раскладки 16388 байт, у 0.6.8 — 6148, то
есть пустой по смыслу).

Поэтому раскладку ставим МЫ и ПОСЛЕДНИМИ — после подписи, перед сжатием образа. Геометрия взята
из платформенного `makedmg.py`, чтобы окно совпадало с нашим фоном пиксель в пиксель; расхождение
этих двух чисел и есть та беда, о которой предупреждает навык `dmg-installer`.

Использование: python3 dmgLayout.py <точка монтирования> <имя приложения без .app>
"""
import os
import struct
import sys

from ds_store import DSStore
from mac_alias import Alias

# Окно 455x296 точек: те же числа, что в makeDmgBackground.sh, иначе фон и иконки разъедутся.
WINDOW_TOP, WINDOW_LEFT, WINDOW_BOTTOM, WINDOW_RIGHT = 100, 400, 396, 855

# Позиции иконок — центры подложек, нарисованных на фоне (56..164 и 279..401 по горизонтали).
APP_POSITION = (110, 167)
APPLICATIONS_POSITION = (340, 167)

# Служебное прячем вправо за край окна: видимым оно превращает окно установки в файловый менеджер.
HIDDEN_POSITIONS = {
    ".background": (560, 170),
    ".DS_Store": (610, 170),
    ".fseventsd": (660, 170),
    ".Trashes": (710, 170),
}

ICON_SIZE = 100.0
TEXT_SIZE = 12.0


def background_name(mount):
    """Имя файла фона внутри тома: оно несёт номер сборки, поэтому ищется, а не задаётся."""
    folder = os.path.join(mount, ".background")
    if not os.path.isdir(folder):
        return None
    names = [n for n in sorted(os.listdir(folder)) if not n.startswith(".")]
    return names[0] if names else None


def write_layout(mount, app_name):
    background = background_name(mount)
    with DSStore.open(os.path.join(mount, ".DS_Store"), "w+") as store:
        for name, position in HIDDEN_POSITIONS.items():
            store[name]["Iloc"] = position
        store["Applications"]["Iloc"] = APPLICATIONS_POSITION
        store["%s.app" % app_name]["Iloc"] = APP_POSITION

        bounds = struct.pack(">H", WINDOW_TOP) + struct.pack(">H", WINDOW_LEFT) + \
                 struct.pack(">H", WINDOW_BOTTOM) + struct.pack(">H", WINDOW_RIGHT) + \
                 bytes("icnv", "ascii") + bytearray([0] * 4)
        store["."]["fwi0"] = ("blob", bounds)
        store["."]["fwsw"] = ("long", 170)
        store["."]["fwvh"] = ("shor", 296)
        store["."]["ICVO"] = ("bool", True)
        store["."]["icvt"] = ("shor", 12)

        options = {
            "viewOptionsVersion": 1,
            "backgroundColorRed": 1.0,
            "backgroundColorGreen": 1.0,
            "backgroundColorBlue": 1.0,
            "gridOffsetX": 0,
            "gridOffsetY": 0,
            "gridSpacing": 100,
            "arrangeBy": "none",
            "showIconPreview": True,
            "showItemInfo": False,
            "labelOnBottom": True,
            "textSize": TEXT_SIZE,
            "iconSize": ICON_SIZE,
            "scrollPositionX": 0.0,
            "scrollPositionY": 0.0,
        }
        if background:
            path = os.path.join(mount, ".background", background)
            options["backgroundType"] = 2
            options["backgroundImageAlias"] = Alias.for_file(path).to_bytes()
        else:
            # Без фона окно всё равно раскладывается: иконки на местах, подписи читаемы. Молчать
            # об этом нельзя — фон исчезает незаметно, а окно без него ничего не объясняет.
            options["backgroundType"] = 1
        store["."]["icvp"] = options
    return background


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("использование: dmgLayout.py <точка монтирования> <имя приложения без .app>")
        sys.exit(1)
    mount_point, application = sys.argv[1], sys.argv[2]
    used = write_layout(mount_point, application)
    print("  раскладка окна записана" + (", фон: %s" % used if used else ", БЕЗ ФОНА"))
