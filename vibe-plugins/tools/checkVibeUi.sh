#!/usr/bin/env bash
# Гейт нашего UI-слоя. Прогонять перед завершением задачи и ОБЯЗАТЕЛЬНО после upstream sync:
# оба правила ловят не ошибки компиляции, а молчаливую деградацию внешнего вида, которую
# иначе замечает только владелец на живой сборке.
#
#   ./vibe-plugins/tools/checkVibeUi.sh
#
# 1. Тонкие скроллы (решение владельца 2026-08-28). Наши панели используют платформенный
#    JBThinOverlappingScrollBar через обёртку com.vibe.agent.ui.VibeScroll. Класс платформы —
#    НЕ публичный контракт: апстрим может его переименовать или убрать, и тогда наш код либо
#    не соберётся, либо (для попапов, где мы обходим дерево компонентов) тихо вернётся к
#    толстым барам. Проверяем, что класс на месте.
# 2. Прямой JBScrollPane в нашем коде — забытый скролл: он останется толстым среди тонких.
#    Новые скроллы создавать только через VibeScroll.pane(...) / VibeScroll.thin(...).

set -euo pipefail
root="$(cd "$(dirname "$0")/../.."; pwd)"
. "$root/vibe-plugins/tools/pythonBin.sh"
status=0

thin_class="$root/platform/platform-api/src/com/intellij/ui/components/JBThinOverlappingScrollBar.kt"
if [[ ! -f "$thin_class" ]]; then
  echo "ОШИБКА: платформенный JBThinOverlappingScrollBar не найден по ожидаемому пути:"
  echo "  $thin_class"
  echo "  Апстрим переименовал или убрал класс — почините com/vibe/agent/ui/VibeScroll.kt,"
  echo "  иначе тонкие скроллы молча исчезнут (в попапах это не даст ошибки компиляции)."
  status=1
fi

# Файл, который создаёт скролл, обязан там же его утоньшить: либо через обёртку VibeScroll,
# либо (в плагинах без доступа к ней) платформенным JBThinOverlappingScrollBar. Проверка
# пофайловая, а не построчная: у скролла бывает подкласс ради getPreferredSize — конструктор
# в таком месте не заменить, бары меняются уже на созданной панели.
offenders=""
while IFS= read -r file; do
  [[ "$file" == *"/ui/VibeScroll.kt" ]] && continue
  # Квалифицированный вызов (javax.swing.JScrollPane(...)) обязан ловиться так же, как короткий:
  # запрет на точку перед именем закрывал ровно тот способ обойти гейт, который пишется, когда
  # импорт не хочется добавлять. Отсекаем только идентификатор вплотную (setJScrollPane).
  grep -qE '(^|[^A-Za-z0-9_])((javax\.swing\.|com\.intellij\.ui\.components\.)?(JBScrollPane|JScrollPane))\(' "$file" || continue
  grep -qE 'VibeScroll\.(pane|thin)|JBThinOverlappingScrollBar' "$file" && continue
  offenders+="  $file"$'\n'
done < <(find "$root/vibe-plugins" -name '*.kt' -not -path '*/testSrc/*')
if [[ -n "$offenders" ]]; then
  echo "ОШИБКА: скролл создан в обход тонких баров — он останется толстым:"
  echo "$offenders"
  echo "  Используйте VibeScroll.pane(view) / VibeScroll.thin(existingPane),"
  echo "  а в плагинах без vibe-agent — JBThinOverlappingScrollBar напрямую."
  status=1
fi

# 3. Правка платформы, раздающая тонкий вид всей IDE (FORK_CHANGES.md). Синк может её снести —
#    внешне это выглядит как «скроллы в дереве проекта опять толстые», без единой ошибки сборки.
jbscrollbar="$root/platform/platform-api/src/com/intellij/ui/components/JBScrollBar.java"
if ! grep -q 'vibeScrollBarThickness' "$jbscrollbar" 2>/dev/null; then
  echo "ОШИБКА: в JBScrollBar.java нет правки [VibeIDEA] (vibeScrollBarThickness)."
  echo "  Скроллы платформы вернутся к штатным 10-14px. См. FORK_CHANGES.md — восстановите правку."
  status=1
fi

# 4. Значки тулвиндоу: четвёрка файлов на значок и совпадение с описанием в makeIcons.py.
#    Значок правят в одном месте из четырёх — и в полосе он меняется, а в Search Everywhere нет;
#    геометрия светлого и тёмного расходится — дёргается выделение. Генератор снимает оба случая.
if ! "$PYTHON" "$root/vibe-plugins/tools/makeIcons.py" --check >/dev/null 2>&1; then
  echo "ОШИБКА: значки тулвиндоу разошлись с описанием vibe-plugins/tools/makeIcons.py."
  "$PYTHON" "$root/vibe-plugins/tools/makeIcons.py" --check 2>&1 | sed 's/^/  /'
  echo "  Правьте ОПИСАНИЕ и перегенерируйте: python3 vibe-plugins/tools/makeIcons.py"
  status=1
fi

# 5. Каждая наша панель — со своим значком: шесть панелей с одной картинкой в полосе неразличимы.
icons_used=$(grep -ho 'icon="/icons/[A-Za-z0-9]*\.svg"' "$root"/vibe-plugins/*/resources/META-INF/plugin.xml | sort | uniq -c | awk '$1 > 1 {print $2}')
if [[ -n "$icons_used" ]]; then
  echo "ОШИБКА: один значок у нескольких панелей — в полосе они неразличимы:"
  printf '%s\n' "$icons_used" | sed 's/^/  /'
  status=1
fi

# 6. Панель без значка и панель, спрятанная во «вторичные». Первое даёт безликий квадрат в полосе,
#    второе прячет её под «...» — и то и другое означает «этой части продукта как будто нет».
while IFS= read -r window; do
  case "$window" in
    *icon=*) ;;
    *) echo "ОШИБКА: тулвиндоу без значка: $(printf '%s' "$window" | sed 's/.*id="\([^"]*\)".*/\1/')"; status=1 ;;
  esac
  case "$window" in
    *secondary=\"true\"*)
      echo "ОШИБКА: тулвиндоу спрятан во вторичные (secondary=true): $(printf '%s' "$window" | sed 's/.*id="\([^"]*\)".*/\1/')"
      status=1 ;;
  esac
done < <("$PYTHON" - "$root" <<'PYWIN'
import glob, io, re, sys
root = sys.argv[1]
for path in sorted(glob.glob(root + '/vibe-plugins/*/resources/META-INF/plugin.xml')):
    text = io.open(path, encoding='utf-8').read()
    for match in re.finditer(r'<toolWindow\b[^>]*/>', text, re.S):
        print(' '.join(match.group(0).split()))
PYWIN
)

# 7. Токены темы: каждый namedColor("Vibe.*") обязан быть объявлен в vibeNeonDark.theme.json.
#    Незаявленный токен НЕ ошибка компиляции и НЕ видна глазами: код молча берёт запасной цвет,
#    и тема просто не красит эту панель. Найдено ревизией 03.09.2026 — шесть таких токенов.
"$PYTHON" - "$root" <<'PYTOKENS' || status=1
import collections, io, json, os, re, sys
root = sys.argv[1]
theme = json.load(io.open(os.path.join(root, 'vibe-plugins/vibe-theme/resources/vibeNeonDark.theme.json'), encoding='utf-8'),
                  object_pairs_hook=collections.OrderedDict)

def flat(obj, prefix=''):
    out = {}
    for key, value in obj.items():
        full = prefix + key
        if isinstance(value, dict):
            out.update(flat(value, full + '.'))
        else:
            out[full] = value
    return out

declared = set(flat({'Vibe': theme['ui'].get('Vibe', {})}))
used = set()
for base, _, files in os.walk(os.path.join(root, 'vibe-plugins')):
    if os.sep + 'src' + os.sep not in base + os.sep:
        continue
    for name in files:
        if name.endswith('.kt'):
            text = io.open(os.path.join(base, name), encoding='utf-8').read()
            used |= set(re.findall(r'namedColor\("(Vibe\.[^"]+)"', text))
missing = sorted(used - declared)
dead = sorted(declared - used)
if missing:
    print('ОШИБКА: токены темы, которые зовёт код, но не объявляет тема (панель не перекрасится):')
    for key in missing:
        print('  ' + key)
if dead:
    print('ОШИБКА: токены объявлены в теме, но никем не используются — мёртвый цвет:')
    for key in dead:
        print('  ' + key)
sys.exit(1 if (missing or dead) else 0)
PYTOKENS

# 8. Страницы настроек: вертикальная прокрутка и ширина по окну.
#    Длинная подсказка без переноса растягивает страницу, и настройки едут вбок — это повторялось
#    трижды (провайдеры, модели, и снова корень + языковые серверы + БД + HTTP на живой 0.4.0).
#    Способ один: VibeScroll.pane(TracksViewportWidthPanel(...)) вокруг содержимого страницы.
while IFS= read -r page; do
  # Ищем ВЫЗОВ, а не имя: неиспользованный импорт остаётся в файле после правки и делал бы
  # проверку зелёной на странице, которая уже не обёрнута (поймано на себе же).
  grep -q 'pane(TracksViewportWidthPanel(' "$page" || {
    echo "ОШИБКА: страница настроек без TracksViewportWidthPanel — она поедет вбок на длинной подсказке:"
    echo "  ${page#"$root"/}"
    echo "  Оберните содержимое: VibeScroll.pane(TracksViewportWidthPanel(...)); см. knowledge/ui/settingsPageWidth.md"
    status=1
  }
done < <(grep -rl 'com.intellij.openapi.options.Configurable\|: Configurable' "$root"/vibe-plugins/*/src --include='*.kt')

# 9. Идентификаторы панелей: только ASCII и только из VibeToolWindows.
#    Идентификатор уезжает в .idea/workspace.xml и в раскладку окон — русская буква там ломается
#    при смене кодировки, а литерал, написанный руками в пятом файле, однажды разойдётся с XML.
python3 - "$root" <<'PYIDS' || status=1
import glob, io, os, re, sys
root = sys.argv[1]
declared = set(re.findall(r'"(Vibe[A-Za-z]*)"',
                          io.open(os.path.join(root, 'vibe-plugins/vibe-agent/src/com/vibe/agent/ui/VibeToolWindows.kt'),
                                  encoding='utf-8').read()))
problems = []
used = set()
for path in sorted(glob.glob(os.path.join(root, 'vibe-plugins/*/resources/META-INF/plugin.xml'))):
    text = io.open(path, encoding='utf-8').read()
    for window in re.findall(r'<toolWindow\b[^>]*id="([^"]+)"', text):
        used.add(window)
        if not window.isascii():
            problems.append(f'идентификатор панели не ASCII: {window} ({os.path.relpath(path, root)})')
        elif window not in declared:
            problems.append(f'идентификатор {window} не объявлен в VibeToolWindows ({os.path.relpath(path, root)})')
# Литерал идентификатора в коде мимо VibeToolWindows — тот самый пятый экземпляр строки.
for base, _, files in os.walk(os.path.join(root, 'vibe-plugins')):
    if os.sep + 'src' + os.sep not in base + os.sep:
        continue
    for name in files:
        if not name.endswith('.kt') or name == 'VibeToolWindows.kt':
            continue
        text = io.open(os.path.join(base, name), encoding='utf-8').read()
        # Ищем именно ОБРАЩЕНИЕ к панели, а не любое совпадение строки: «VibeHttp» — это ещё и
        # идентификатор нашего языка (Language("VibeHttp")), и запрещать его было бы ложной
        # тревогой (поймано на себе при первом прогоне гейта).
        for line in text.split('\n'):
            if 'getToolWindow(' not in line and 'TOOL_WINDOW' not in line:
                continue
            for window in used:
                if f'"{window}"' in line:
                    problems.append(f'{name}: идентификатор панели написан литералом «{window}» — возьмите VibeToolWindows')
for problem in problems:
    print('ОШИБКА: ' + problem)
sys.exit(1 if problems else 0)
PYIDS

[[ $status -eq 0 ]] && echo "UI-гейт: тонкие скроллы на месте (наши панели + правка платформы), обходов VibeScroll нет; значки на месте и различимы"
exit $status
