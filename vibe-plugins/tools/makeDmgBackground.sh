#!/usr/bin/env bash
# Собирает фон установочного окна из dmgBackground.svg в двухстраничный TIFF.
#
# Зачем две страницы: Finder сам берёт страницу 2x на ретине, а одностраничный 1x мылит на любом
# маке за последние десять лет. Склейка — системным tiffutil, как в навыке dmg-installer.
#
# Картинка коммитится собранной: сборка дистрибутива принимает путь к готовому файлу и не должна
# зависеть от наличия rsvg-convert на машине сборщика. Скрипт нужен, когда меняется рисунок.
#
# Требуется: rsvg-convert (brew install librsvg). tiffutil — системный.
set -euo pipefail
cd "$(dirname "$0")/../.."

SRC=vibeidea-customization/resources/mac/dmgBackground.svg
OUT=vibeidea-customization/resources/mac/dmgBackground.tiff
# Размер окна установки задаёт платформа (makedmg.py, блоб fwi0): 455x296 точек. Меняется там —
# меняется и здесь, иначе картинка перестанет совпадать с раскладкой иконок.
WIDTH=455
HEIGHT=296

command -v rsvg-convert >/dev/null || { echo "✖ нет rsvg-convert: brew install librsvg"; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

rsvg-convert -w "$WIDTH" -h "$HEIGHT" "$SRC" -o "$TMP/bg-1x.png"
rsvg-convert -w $((WIDTH * 2)) -h $((HEIGHT * 2)) "$SRC" -o "$TMP/bg-2x.png"
tiffutil -cathidpicheck "$TMP/bg-1x.png" "$TMP/bg-2x.png" -out "$OUT" >/dev/null

echo "  фон собран: $OUT ($(wc -c < "$OUT" | tr -d ' ') байт, страницы ${WIDTH}x${HEIGHT} и $((WIDTH * 2))x$((HEIGHT * 2)))"
