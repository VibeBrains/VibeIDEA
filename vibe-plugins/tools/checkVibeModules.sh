#!/usr/bin/env bash
# Гейт модели проекта: зависимости наших модулей объявлены ОДИНАКОВО в .iml и в BUILD.bazel.
#
# Повод — 07.09.2026, третий случай подряд из одного семейства. Состав нашего кода описан в двух
# местах (JPS-модель в `.iml` и граф Bazel в `BUILD.bazel`), и юнит-тесты видят только второе:
# `bazel test` собирает модуль и молчит, а генератор bazel-targets.json обходит ВСЮ модель проекта
# и падает на первой неразрешённой ссылке — то есть узнаёшь об этом сборкой инсталлятора, через
# двадцать минут после коммита.
#
# Проверяется ровно то, что ломалось: ссылка на библиотеку уровня проекта, которой нет в
# .idea/libraries, и ссылка на модуль, которого нет в дереве.
set -euo pipefail
cd "$(dirname "$0")/../.."
. vibe-plugins/tools/pythonBin.sh

say() { printf '%s\n' "$*"; }
fail=0

"$PYTHON" - <<'PYMODULES' || fail=1
import io, os, re, sys

# Имя библиотеки в .idea/libraries: точки и дефисы файл заменяет подчёркиванием.
def library_file(name):
    return re.sub(r'[.\- ]', '_', name) + '.xml'

known_modules = set()
for root, _, files in os.walk('.'):
    if any(part in root for part in ('/out/', '/.git/', '/node_modules/')):
        continue
    for name in files:
        if name.endswith('.iml'):
            known_modules.add(name[:-4])
        elif name == 'BUILD.bazel':
            text = io.open(os.path.join(root, name), encoding='utf-8', errors='ignore').read()
            known_modules.update(re.findall(r'module_name\s*=\s*"([^"]+)"', text))

problems = []
for root, _, files in os.walk('vibe-plugins'):
    for name in files:
        if not name.endswith('.iml'):
            continue
        path = os.path.join(root, name)
        text = io.open(path, encoding='utf-8').read()
        for library in re.findall(r'<orderEntry type="library"[^>]*name="([^"]+)"[^>]*level="project"', text):
            if not os.path.isfile(os.path.join('.idea', 'libraries', library_file(library))):
                problems.append(f"{path}: библиотеки уровня проекта «{library}» нет в .idea/libraries")
        for module in re.findall(r'<orderEntry type="module" module-name="([^"]+)"', text):
            if module not in known_modules:
                problems.append(f"{path}: модуля «{module}» нет в дереве")

if problems:
    print("✖ модель проекта и граф сборки разошлись:")
    for problem in problems:
        print("   ", problem)
    print("  Ссылку в .iml проверяет ТОЛЬКО сборка инсталлятора: генератор bazel-targets.json")
    print("  падает на неразрешённой зависимости, а `bazel test` до неё не доходит.")
    sys.exit(1)
print("  зависимости наших модулей: ссылки .iml разрешаются целиком")
PYMODULES

if [ "$fail" -ne 0 ]; then
  say "Гейт модели проекта: ПРОВАЛЕН"
  exit 1
fi
say "Гейт модели проекта: .iml и граф сборки согласованы"
