#!/usr/bin/env python3
"""Собрать TextMate-бандлы из распакованного пакета tm-grammars.

Бандл в понимании платформы — это папка в форме расширения VS Code: `package.json` с разделами
`contributes.languages` и `contributes.grammars` плюс сами грамматики. Собираем её здесь, а не
кладём в репозиторий руками, по одной причине: `scopeName` каждой грамматики берётся ИЗ САМОЙ
грамматики. Скопированное руками имя области однажды разойдётся с файлом, и разойдётся молча —
подсветка просто не включится, а искать будут в коде плагина.

Вызов: textmateBundles.py <распакованный tm-grammars/package> <куда класть бандлы>
"""
import json
import pathlib
import shutil
import sys

# Из чего собирается каждый бандл: расширения файлов и грамматики (основная первой).
#
# У Vue грамматик пять: сам однофайловый компонент, его разметка и три вложенные области, на
# которые он ссылается (`vue-html`, `vue-directives`, `vue-interpolations` и внедрение переменных
# в стиль). Без них файл подсвечивается наполовину: тег виден, директива внутри — нет.
BUNDLES = {
    "vue": {
        "language": "vue",
        "display": "Vue",
        "extensions": [".vue"],
        "grammars": ["vue", "vue-html", "vue-directives", "vue-interpolations",
                     "vue-sfc-style-variable-injection"],
    },
    "svelte": {
        "language": "svelte",
        "display": "Svelte",
        "extensions": [".svelte"],
        "grammars": ["svelte"],
    },
    "astro": {
        "language": "astro",
        "display": "Astro",
        "extensions": [".astro"],
        "grammars": ["astro"],
    },
    "sass": {
        "language": "sass",
        "display": "Sass (indented)",
        "extensions": [".sass"],
        "grammars": ["sass"],
    },
    "stylus": {
        "language": "stylus",
        "display": "Stylus",
        "extensions": [".styl", ".stylus"],
        "grammars": ["stylus"],
    },
}


def scope_of(path: pathlib.Path) -> str:
    """Имя области — из самой грамматики. Своего мнения об этом у нас нет и быть не может."""
    with path.open(encoding="utf-8") as handle:
        scope = json.load(handle).get("scopeName")
    if not scope:
        raise SystemExit(f"✖ в грамматике нет scopeName: {path}")
    return scope


def build(source: pathlib.Path, target: pathlib.Path, version: str) -> None:
    grammars_dir = source / "grammars"
    if not grammars_dir.is_dir():
        raise SystemExit(f"✖ каталог грамматик не найден: {grammars_dir}")

    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)

    for name, spec in BUNDLES.items():
        bundle = target / name
        syntaxes = bundle / "syntaxes"
        syntaxes.mkdir(parents=True)

        grammars = []
        for index, grammar in enumerate(spec["grammars"]):
            src = grammars_dir / f"{grammar}.json"
            if not src.is_file():
                raise SystemExit(f"✖ грамматика {grammar}.json отсутствует в пакете")
            dst = syntaxes / f"{grammar}.tmLanguage.json"
            shutil.copyfile(src, dst)
            entry = {"scopeName": scope_of(dst), "path": f"./syntaxes/{dst.name}"}
            # Язык объявляет только ОСНОВНАЯ грамматика: вложенные области сами по себе файлами
            # не бывают, и объявленный на них язык дал бы тип файла без единого расширения.
            if index == 0:
                entry["language"] = spec["language"]
            grammars.append(entry)

        manifest = {
            "name": name,
            "displayName": spec["display"],
            "version": version,
            "description": f"{spec['display']} syntax for VibeIDEA, from tm-grammars {version}.",
            "contributes": {
                "languages": [{
                    "id": spec["language"],
                    "aliases": [spec["display"], spec["language"]],
                    "extensions": spec["extensions"],
                }],
                "grammars": grammars,
            },
        }
        with (bundle / "package.json").open("w", encoding="utf-8") as handle:
            json.dump(manifest, handle, indent=2, ensure_ascii=False)
            handle.write("\n")

    # Лицензии обязаны ехать рядом с копией: MIT требует этого прямым текстом, а провенанс каждой
    # грамматики (чей репозиторий, чья лицензия) есть только в NOTICE пакета.
    for legal in ("LICENSE", "NOTICE"):
        src = source / legal
        if src.is_file():
            shutil.copyfile(src, target / f"tm-grammars-{legal}")
    (target / "README.txt").write_text(
        f"TextMate grammars for Vue, Svelte, Astro, Sass and Stylus, from tm-grammars {version}.\n"
        "The grammars are third-party; their origin and licences are in tm-grammars-NOTICE.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("использование: textmateBundles.py <tm-grammars/package> <каталог бандлов> <версия>")
    build(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3])
    print(f"  TextMate-бандлы собраны: {', '.join(BUNDLES)}")
