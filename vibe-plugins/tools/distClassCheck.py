#!/usr/bin/env python3
"""Классы, на которые ссылается наш код, но которых нет в дистрибутиве.

Повод: 18.09.2026. `CodeGraphBuilder` брал символы из UAST, `.iml` объявлял модуль
`intellij.platform.uast`, компиляция и 1857 тестов были зелёными — а в установленной IDE класса нет:
UAST лежит ВНУТРИ плагина Java, и построение графа падало `NoClassDefFoundError` во всех сборках
0.6.x. Юнит-тест такое не видит по построению: в тестах classpath шире, чем в образе.

Что делаем: читаем пул констант каждого нашего класса, собираем имена, на которые он ссылается, и
сверяем с тем, что плагин РЕАЛЬНО видит в образе — платформа (`lib/`, `lib/modules/`) плюс его
собственные библиотеки. Ссылка на класс чужого плагина законна только через объявленную
зависимость, поэтому такие имена перечислены в distClassAllowlist.txt с причиной.
"""
import io
import os
import struct
import sys
import zipfile

JDK_PREFIXES = ("java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/dom", "org/xml/sax",
                "org/ietf/jgss", "netscape/javascript")

# Разбор пула констант: нас интересует только тег 7 (класс) и тег 1 (строка с именем).
WIDTHS = {3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4, 8: 2, 16: 2, 19: 2, 20: 2, 15: 3}


def referenced_classes(data):
    """Имена классов из пула констант одного .class-файла."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return set()
    count = struct.unpack_from(">H", data, 8)[0]
    pos = 10
    utf8, class_refs = {}, []
    index = 1
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, pos)[0]
            utf8[index] = data[pos + 2:pos + 2 + length].decode("utf-8", "replace")
            pos += 2 + length
        elif tag == 7:
            class_refs.append(struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (5, 6):          # long и double занимают ДВА места в пуле
            pos += 8
            index += 1
        else:
            pos += WIDTHS.get(tag, 2)
        index += 1
    return {utf8[i] for i in class_refs if i in utf8}


def classes_in(jars):
    found = set()
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as zf:
                found.update(n[:-6] for n in zf.namelist() if n.endswith(".class"))
        except (OSError, zipfile.BadZipFile):
            continue
    return found


def jars_under(*dirs):
    out = []
    for directory in dirs:
        if not os.path.isdir(directory):
            continue
        for root, _, files in os.walk(directory):
            out += [os.path.join(root, f) for f in files if f.endswith(".jar")]
    return out


def declared_dependencies(plugin_jars):
    """Идентификаторы из <depends> дескриптора — читаем тот дескриптор, который реально уехал."""
    ids = []
    for jar in plugin_jars:
        try:
            with zipfile.ZipFile(jar) as zf:
                if "META-INF/plugin.xml" not in zf.namelist():
                    continue
                text = zf.read("META-INF/plugin.xml").decode("utf-8", "replace")
        except (OSError, zipfile.BadZipFile, KeyError):
            continue
        for chunk in text.split("<depends")[1:]:
            body = chunk.split(">", 1)[1].split("</depends", 1)[0] if ">" in chunk else ""
            if body.strip():
                ids.append(body.strip())
    return ids


def allowed_by(patterns, name):
    """Имя целиком или семейство с `*` на конце — как в списке исключений брендинга."""
    dotted = name.replace("/", ".")
    return any(dotted == p or (p.endswith("*") and dotted.startswith(p[:-1])) for p in patterns)


def main():
    root, plugins_dir, allowlist_path = sys.argv[1], sys.argv[2], sys.argv[3]
    allowed = set()
    if os.path.isfile(allowlist_path):
        with io.open(allowlist_path, encoding="utf-8") as f:
            for line in f:
                name = line.split("#", 1)[0].strip()
                if name:
                    allowed.add(name)
    platform = jars_under(os.path.join(root, "lib"))
    visible_platform = classes_in(platform)
    bad = 0
    for plugin in sorted(os.listdir(plugins_dir)):
        if not plugin.startswith("vibe-"):
            continue
        own = jars_under(os.path.join(plugins_dir, plugin))
        # Свои классы — только из jar-ов с нашими пакетами: библиотеки внутри плагина проверять
        # незачем, они приехали целиком вместе со своими зависимостями.
        mine = [j for j in own if os.path.basename(j).startswith(plugin)]
        # Объявленная зависимость на соседний наш плагин — законный источник классов: платформа
        # ставит его загрузчик родителем. Незаявленная — тот самый дефект, который ищем.
        neighbours = []
        for dependency in declared_dependencies(mine):
            if dependency.startswith("com.vibe."):
                neighbours += jars_under(os.path.join(plugins_dir, "vibe-" + dependency.split(".")[-1]))
        visible = visible_platform | classes_in(own) | classes_in(neighbours)
        missing = {}
        for jar in mine:
            with zipfile.ZipFile(jar) as zf:
                for entry in zf.namelist():
                    if not entry.endswith(".class"):
                        continue
                    for name in referenced_classes(zf.read(entry)):
                        if name.startswith("[") or name in visible or allowed_by(allowed, name):
                            continue
                        if name.startswith(JDK_PREFIXES):
                            continue
                        missing.setdefault(name, entry[:-6])
        if missing:
            bad = 1
            print("✖ %s ссылается на классы, которых в образе не видит:" % plugin)
            for name in sorted(missing)[:20]:
                print("    %s  (из %s)" % (name.replace("/", "."), missing[name].replace("/", ".")))
            if len(missing) > 20:
                print("    … и ещё %d" % (len(missing) - 20))
            print("  Это NoClassDefFoundError в работающей IDE. Либо класс лежит в чужом плагине —")
            print("  тогда нужна объявленная зависимость и строка в distClassAllowlist.txt с причиной,")
            print("  либо от него надо избавиться (разбор — knowledge/build/platformClassesInDist.md).")
    return bad


if __name__ == "__main__":
    sys.exit(main())
