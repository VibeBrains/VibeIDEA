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

# 3а. Скроллы HTML-панелей (превью Markdown и Mermaid, JCEF-просмотрщик картинок) рисует CSS
#     JBCefScrollbarsHelper, а не JBScrollBar. Без этой правки они остаются 14px рядом с тонкими.
jcefscroll="$root/platform/ui.jcef/jcef/JBCefScrollbarsHelper.java"
if ! grep -q 'vibeScrollBarThickness' "$jcefscroll" 2>/dev/null; then
  echo "ОШИБКА: в JBCefScrollbarsHelper.java нет правки [VibeIDEA] (vibeScrollBarThickness)."
  echo "  Скроллы HTML-панелей вернутся к штатным 14px. См. FORK_CHANGES.md — восстановите правку."
  status=1
fi

# 3б. Скролл РЕДАКТОРА тонкий не от той же правки: при включённой полосе разметки редактор ставит
#     скроллу собственный UI (MyErrorPanel), минуя JBScrollBar.createUI, и берёт ширину бегунка из
#     UI-свойства «Editor.scrollBarWidth». Мы кладём туда свою толщину — три точки, и потеря любой
#     выглядит как «в редакторе опять толстый скролл», без единой ошибки сборки.
editor_width="$root/vibe-plugins/vibe-agent/src/com/vibe/agent/ui/EditorScrollBarWidth.kt"
if ! grep -qF '"Editor.scrollBarWidth"' "$editor_width" 2>/dev/null; then
  echo "ОШИБКА: нет EditorScrollBarWidth с UI-свойством Editor.scrollBarWidth."
  echo "  Скролл в редакторе вернётся к платформенным 14px независимо от настройки толщины."
  status=1
else
  for place in \
    "$root/vibe-plugins/vibe-agent/src/com/vibe/agent/appearance/CompactModeStartup.kt:при старте" \
    "$root/vibe-plugins/vibe-agent/src/com/vibe/agent/settings/VibeUiConfigurable.kt:при смене настройки"; do
    file="${place%%:*}"; when="${place##*:}"
    if ! grep -qF 'EditorScrollBarWidth.apply()' "$file" 2>/dev/null; then
      echo "ОШИБКА: EditorScrollBarWidth.apply() не вызывается $when ($file)."
      status=1
    fi
  done
  # Смена темы пересоздаёт значения UIManager: без слушателя настройка «работает, пока не поменяешь
  # тему», а это худший вид работающей настройки — ломается позже и без связи с причиной.
  if ! grep -qF 'EditorScrollBarWidthLafListener' "$root/vibe-plugins/vibe-agent/resources/META-INF/plugin.xml" 2>/dev/null; then
    echo "ОШИБКА: слушатель смены темы EditorScrollBarWidthLafListener не объявлен в plugin.xml."
    status=1
  fi
fi

# 3в. Ссылки платформы в НАШИХ панелях. Подчёркнутый синий текст обещает переход по ссылке, а в
#     панели это действие — так и появилось «кнопочек хочется» от владельца 08.09.2026. В страницах
#     настроек ActionLink оставлен намеренно: там это родная идиома платформы, и своя кнопка среди
#     платформенных выглядела бы чужой.
links=$("$PYTHON" - "$root" <<'PYLINKS'
import io, os, sys
root = sys.argv[1]
bad = []
for base, _, files in os.walk(os.path.join(root, 'vibe-plugins')):
    if '/testSrc/' in base or '/out/' in base:
        continue
    for name in files:
        if not name.endswith('Panel.kt') and not name.endswith('View.kt'):
            continue
        path = os.path.join(base, name)
        if 'ActionLink(' in io.open(path, encoding='utf-8').read():
            bad.append(os.path.relpath(path, root))
print('\n'.join(sorted(bad)))
PYLINKS
)
if [ -n "$links" ]; then
  echo "ОШИБКА: в панелях используются ссылки платформы вместо кнопок:"
  printf '%s\n' "$links" | sed 's/^/    /'
  echo "  Действие в панели — кнопка: PillButton(text, outlined = true) { … }."
  echo "  ActionLink оставлен только страницам настроек — там это идиома платформы."
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
# Проверяются ВСЕ наши темы, а не одна: токен, объявленный только в первой, оставляет остальные
# темы некрашеными — и это ровно тот дефект, который тема и обязана была закрыть (18.09.2026,
# когда тем стало семь).
themes_dir = os.path.join(root, 'vibe-plugins/vibe-theme/resources')
themes = {}
for theme_name in sorted(os.listdir(themes_dir)):
    if theme_name.endswith('.theme.json'):
        themes[theme_name] = json.load(io.open(os.path.join(themes_dir, theme_name), encoding='utf-8'),
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

declared_per_theme = {name: set(flat({'Vibe': body['ui'].get('Vibe', {})})) for name, body in themes.items()}
declared = set.intersection(*declared_per_theme.values()) if declared_per_theme else set()
used = set()
for base, _, files in os.walk(os.path.join(root, 'vibe-plugins')):
    if os.sep + 'src' + os.sep not in base + os.sep:
        continue
    for name in files:
        if name.endswith('.kt'):
            text = io.open(os.path.join(base, name), encoding='utf-8').read()
            used |= set(re.findall(r'namedColor\("(Vibe\.[^"]+)"', text))
missing = sorted(used - declared)
for theme_name, theme_tokens in sorted(declared_per_theme.items()):
    gaps = sorted(used - theme_tokens)
    if gaps:
        print('ОШИБКА: тема %s не объявляет токены: %s' % (theme_name, ', '.join(gaps)))
        sys.exit(1)
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
#    Дефект «страница едет вбок» возвращался ЧЕТЫРЕЖДЫ (провайдеры, модели, корень + БД + HTTP на
#    живой 0.4.0 и языковые серверы на 0.6.3), и каждый раз чинился наполовину, потому что причин
#    у него две:
#      — обёртка страницы: вид следует ширине окна, минимум нулевой, горизонтальной полосы нет;
#      — подсказка: JBLabel("<html>…") сообщает ширину В ОДНУ СТРОКУ и растягивает страницу сам.
#    Обе формы живут в SettingsUi: страница — SettingsUi.page(...), подсказка — SettingsUi.hint(...).
while IFS= read -r page; do
  # Ищем ВЫЗОВ, а не имя: неиспользованный импорт остаётся в файле после правки и делал бы
  # проверку зелёной на странице, которая уже не обёрнута (поймано на себе же).
  grep -q 'SettingsUi.page(' "$page" || {
    echo "ОШИБКА: страница настроек не обёрнута SettingsUi.page(...) — она поедет вбок:"
    echo "  ${page#"$root"/}"
    echo "  Оберните содержимое: SettingsUi.page(...); см. knowledge/ui/settingsPageWidth.md"
    status=1
  }
  if grep -q 'JBLabel("<html>' "$page"; then
    echo "ОШИБКА: сырой html-JBLabel на странице настроек — он просит ширину всей фразы в одну строку:"
    echo "  ${page#"$root"/}"
    echo "  Замените на SettingsUi.hint(...) или SettingsUi.section(...); см. knowledge/ui/settingsPageWidth.md"
    status=1
  fi
  # Литерала мало: длинный текст приходит КЛЮЧОМ каталога, и `JBLabel(t("settings.root.html"))`
  # проверку на литерал проходил насквозь — ровно так корневая страница и ехала вбок при зелёном
  # гейте (владелец, 19.09.2026). Поэтому смотрим на САМУ СТРОКУ в каталоге, а не на её запись.
  "$PYTHON" - "$root" "$page" <<'PYHINT' || status=1
import io, json, os, re, sys
root, page = sys.argv[1], sys.argv[2]
catalog = json.load(io.open(os.path.join(root, 'vibe-plugins/vibe-agent/resources/lang/base.json'), encoding='utf-8'))
text = io.open(page, encoding='utf-8').read()
LONG = 60
bad = []
for key in re.findall(r'JBLabel\(\s*t\(\s*"([^"]+)"', text):
    value = catalog.get(key)
    if value is None:
        continue
    if '<html' in value or len(value) > LONG:
        bad.append((key, len(value)))
if bad:
    print('ОШИБКА: длинный текст в сыром JBLabel на странице настроек — он просит ширину в одну строку:')
    print('  ' + os.path.relpath(page, root))
    for key, size in bad:
        print('    %s — %d символов' % (key, size))
    print('  Замените на SettingsUi.hint(...); перенос проверяется замером в SettingsHintWidthTest')
    sys.exit(1)
PYHINT
done < <(grep -rl 'com.intellij.openapi.options.Configurable\|: Configurable' "$root"/vibe-plugins/*/src --include='*.kt')

# 8б. Подсказка ДЕЙСТВИТЕЛЬНО переносится — это меряется, а не выводится из формы вызова.
#
# Четыре предыдущих захода чинили форму и были зелёными; на пятый раз владелец прислал шесть
# вкладок с обрезанным текстом. Замер показал, что подсказка просила 1702 точки при выданных 420 и
# не меняла высоту при сужении вовсе. Поэтому гейт спрашивает результат у самого компонента.
#
# Вывод НЕ глушится в /dev/null: «тест упал» и «тест не удалось прогнать» — разные новости, и
# общее сообщение об одной врёт про другую. Гейт, который на сбой сборки говорит «подсказка не
# переносится», отправляет человека чинить работающее — ровно та ошибка, за которую этот файл уже
# ругал сам себя выше.
echo "  подсказка настроек: перенос проверяется замером"
HINT_OUT=$(cd "$root" && ./bazel.cmd test //vibe-plugins/vibe-agent:vibe-agent_test --test_filter=SettingsHintWidth 2>&1) || true
case "$HINT_OUT" in
  *"tests pass"*|*"test passes"*)
    : ;;
  *"FAILED"*|*"failing"*)
    echo "ОШИБКА: подсказка настроек не переносится по ширине — страница обрежет текст по правому краю"
    printf '%s\n' "$HINT_OUT" | grep -F 'AssertionFailedError' | head -3 | sed 's/^/    /'
    echo "  Прогоните: ./bazel.cmd test //vibe-plugins/vibe-agent:vibe-agent_test --test_filter=SettingsHintWidth"
    status=1 ;;
  *)
    echo "ОШИБКА: замер переноса НЕ ВЫПОЛНЕН — это не приговор подсказке, а невозможность её проверить"
    printf '%s\n' "$HINT_OUT" | tail -3 | sed 's/^/    /'
    status=1 ;;
esac

# 8б2. Списки на страницах настроек — только сжимаемые.
#
# У платформенного `ComboBox` минимальная ширина РАВНА предпочтительной, а та растёт с длиной
# самого длинного пункта: один такой список поднимает минимум всей формы до своего, и страница
# перестаёт сжиматься. Горизонтальной полосы у неё нет (мы её выключили сами), поэтому всё правее
# пола не прокручивается, а обрезается — владелец прислал это со страницы языковых серверов
# 20.09.2026, третьим заходом вокруг одного дефекта.
#
# Замер держит `SettingsPageShrinkTest`; здесь запрещается сам прямой вызов, потому что он
# возвращает дефект целиком одной строкой.
BAD_COMBO=$(grep -rn "ComboBox(" vibe-plugins/*/src --include='*.kt' \
  | grep -E "Configurable\.kt" \
  | grep -v "SettingsUi.combo(" \
  | grep -vE ':[0-9]+:[[:space:]]*(//|\*)' || true)
if [ -n "$BAD_COMBO" ]; then
  echo "ОШИБКА: список настроек создан напрямую — он не сожмётся, и страницу обрежет по правому краю"
  echo "$BAD_COMBO" | sed 's/^/    /'
  echo "  Зовите SettingsUi.combo(...): он снимает платформенный флаг и назначает минимум явно"
  status=1
else
  echo "  списки настроек: все сжимаемые"
fi

# 8в. Образец кода на странице «Оформление» действительно будет нарисован.
#
# Страница рисует рядом с каждой темой кусочек кода цветами ЕЁ схемы редактора. Схема, не
# объявившая такой цвет, отдаёт цвет РОДИТЕЛЬСКОЙ схемы — выбранный для другого фона и другой
# палитры, — и образец тихо врёт. Проверяется и то, что схема объявляет общий минимум: семь наших
# схем объявляли восемь атрибутов, восьмая (неоновая, наша подпись) — пять, и разница была не
# задумана, а забыта. Гейт контраста этого поймать не мог по построению: он меряет объявленное, а
# не отсутствующее (20.09.2026).
"$PYTHON" - "$root" <<'PYSWATCH' || status=1
import collections, glob, io, json, os, re, sys
root = sys.argv[1]
page = os.path.join(root, 'vibe-plugins/vibe-agent/src/com/vibe/agent/settings/VibeAppearanceConfigurable.kt')
text = io.open(page, encoding='utf-8').read()
match = re.search(r'PREVIEW_TOKENS\s*=\s*listOf\(([^)]*)\)', text)
if not match:
    print('ОШИБКА: в VibeAppearanceConfigurable не найден список PREVIEW_TOKENS')
    sys.exit(1)
# Внешнее имя ключа в схеме — «DEFAULT_» + имя константы (DefaultLanguageHighlighterColors.java).
tokens = ['DEFAULT_' + name for name in re.findall(r'DefaultLanguageHighlighterColors\.([A-Z_]+)', match.group(1))]
if not tokens:
    print('ОШИБКА: PREVIEW_TOKENS пуст — образец темы нарисуется голым прямоугольником')
    sys.exit(1)
bad = []
for path in sorted(glob.glob(os.path.join(root, 'vibe-plugins/vibe-theme/resources/vibe*Scheme.xml'))):
    body = io.open(path, encoding='utf-8').read()
    missing = [key for key in tokens
               if not re.search(r'<option name="%s">\s*<value>\s*<option name="FOREGROUND"' % key, body)]
    # Фон образца берётся оттуда же, откуда его берёт редактор.
    if not re.search(r'<option name="TEXT">\s*<value>[\s\S]*?<option name="BACKGROUND"', body):
        missing.append('TEXT/BACKGROUND')
    if missing:
        bad.append((os.path.basename(path), missing))
if bad:
    print('ОШИБКА: схема темы не объявляет того, что рисует образец на странице «Оформление»:')
    for name, missing in bad:
        print('    %s — нет %s' % (name, ', '.join(missing)))
    print('  Образец показывает, как в теме выглядит КОД; без этих цветов он выйдет пустым прямоугольником')
    sys.exit(1)
# Общий минимум — то, что объявляют все наши схемы. Список не «из головы»: он снят с семи схем,
# писавшихся подряд, и восьмая отстала от него молча.
MINIMUM = ('DEFAULT_KEYWORD', 'DEFAULT_STRING', 'DEFAULT_NUMBER', 'DEFAULT_LINE_COMMENT',
           'DEFAULT_BLOCK_COMMENT', 'DEFAULT_FUNCTION_DECLARATION', 'DEFAULT_CLASS_NAME')
thin = []
for path in sorted(glob.glob(os.path.join(root, 'vibe-plugins/vibe-theme/resources/vibe*Scheme.xml'))):
    body = io.open(path, encoding='utf-8').read()
    missing = [key for key in MINIMUM
               if not re.search(r'<option name="%s">\s*<value>\s*<option name="FOREGROUND"' % key, body)]
    if missing:
        thin.append((os.path.basename(path), missing))
if thin:
    print('ОШИБКА: схема темы не объявляет общего минимума и возьмёт цвета родительской схемы:')
    for name, missing in thin:
        print('    %s — нет %s' % (name, ', '.join(missing)))
    print('  Родительские цвета выбраны для другого фона: тема выглядит наполовину чужой,')
    print('  а гейт контраста этого не измерит — он меряет объявленное, а не отсутствующее')
    sys.exit(1)
print('  образец кода: все %d схем дают фон и %s' % (
    len(glob.glob(os.path.join(root, 'vibe-plugins/vibe-theme/resources/vibe*Scheme.xml'))), ', '.join(tokens)))
print('  общий минимум схем: %d атрибутов у каждой' % len(MINIMUM))

# Реестр тем и файлы на диске обязаны совпадать В ОБЕ СТОРОНЫ, и схема редактора обязана быть.
# Такую ошибку видит только запущенная IDE: объявленная, но отсутствующая тема просто не появится
# в списке, а тема без своей схемы откроет редактор чужими цветами. Ни компиляция, ни тесты про
# содержимое чужого json не знают.
themes_dir = os.path.join(root, 'vibe-plugins/vibe-theme/resources')
descriptor = io.open(os.path.join(themes_dir, 'META-INF/plugin.xml'), encoding='utf-8').read()
declared = set(re.findall(r'<themeProvider[^>]*path="([^"]+)"', descriptor))
present = {'/' + os.path.basename(p) for p in glob.glob(os.path.join(themes_dir, 'vibe*.theme.json'))}
problems = []
for path in sorted(declared - present):
    problems.append('объявлена в plugin.xml, но файла нет: %s' % path)
for path in sorted(present - declared):
    problems.append('файл есть, но в plugin.xml не объявлен — темы не будет в списке: %s' % path)
sides = []
for path in sorted(glob.glob(os.path.join(themes_dir, 'vibe*.theme.json'))):
    theme = json.load(io.open(path, encoding='utf-8'))
    sides.append(bool(theme.get('dark')))
    for field in ('name', 'dark'):
        if field not in theme:
            problems.append('%s: нет обязательного поля «%s»' % (os.path.basename(path), field))
    scheme = theme.get('editorScheme')
    if not scheme:
        problems.append('%s: нет editorScheme — редактор откроется чужими цветами' % os.path.basename(path))
    elif not os.path.isfile(os.path.join(themes_dir, scheme.lstrip('/'))):
        problems.append('%s: схема редактора %s не найдена' % (os.path.basename(path), scheme))
# В наборе обязаны быть обе стороны. Пара «день/ночь» на странице «Оформление» берёт дневную тему
# из светлых, ночную из тёмных: набор из одних тёмных делает половину пары чужой, и узнаёт об этом
# человек, а не гейт (до 19.09.2026 все семь наших тем были тёмными).
if sides and (all(sides) or not any(sides)):
    problems.append('в наборе только %s темы — вторая половина пары «день/ночь» будет чужой'
                    % ('тёмные' if all(sides) else 'светлые'))
if problems:
    print('ОШИБКА: реестр тем разошёлся с файлами:')
    for line in problems:
        print('    ' + line)
    sys.exit(1)
# Повторённый литерал обязан иметь имя в палитре.
#
# Это ровно то, о чём наш собственный дизайн-детектор предупреждает чужие страницы: палитра
# расползается по одному hex за раз, и каждый шаг выглядит нормально. Замер 20.09.2026: в каждой из
# восьми тем 7–8 цветов были повторены до восьми раз — правка тона означала восемь одинаковых замен,
# и разъезд был бы молчаливым. Цвета проекта не в счёт: их пишет генератор.
spread = []
for path in sorted(glob.glob(os.path.join(themes_dir, 'vibe*.theme.json'))):
    counts = collections.Counter()

    def count(node, prefix=''):
        for key, value in node.items():
            full = prefix + key
            if isinstance(value, dict):
                count(value, full + '.')
            elif isinstance(value, str) and value.startswith('#') and not full.startswith('RecentProject'):
                counts[value] += 1

    count(json.load(io.open(path, encoding='utf-8')).get('ui', {}))
    repeated = sorted('%s ×%d' % (colour, times) for colour, times in counts.items() if times >= 3)
    if repeated:
        spread.append((os.path.basename(path), repeated))
if spread:
    print('ОШИБКА: цвет повторён в теме трижды и не назван — палитра расползается по одному hex:')
    for name, repeated in spread:
        print('    %s — %s' % (name, ', '.join(repeated)))
    print('  Заведите запись в секции «colors» темы и подставьте её имя вместо литерала:')
    print('  иначе правка тона это N одинаковых замен, и разъезд первой же из них никто не заметит')
    sys.exit(1)
# Пара «день/ночь» засевается по ИДЕНТИФИКАТОРАМ тем — их легко переименовать и не заметить:
# засев молча не сработает, а человек увидит в паре чужие темы и решит, что так и задумано.
pair = os.path.join(root, 'vibe-plugins/vibe-agent/src/com/vibe/agent/appearance/ThemePairDefault.kt')
declared_ids = set(re.findall(r'<themeProvider[^>]*id="([^"]+)"', descriptor))
for field, value in re.findall(r'const val (LIGHT_ID|DARK_ID): String = "([^"]+)"',
                               io.open(pair, encoding='utf-8').read()):
    if value not in declared_ids:
        problems.append('ThemePairDefault.%s = %s — такой темы нет в plugin.xml, засев пары промолчит'
                        % (field, value))
if problems:
    print('ОШИБКА: реестр тем разошёлся с файлами:')
    for line in problems:
        print('    ' + line)
    sys.exit(1)
print('  темы: %d объявлено и на месте, у каждой своя схема редактора — тёмных %d, светлых %d'
      % (len(present), sum(sides), len(sides) - sum(sides)))
print('  пара «день/ночь»: обе засеваемые темы объявлены')
# И обратное: запись палитры, которой никто не пользуется, — мёртвый цвет.
#
# Так у неоновой темы жили `NeonCyan` и `Accent` с одним и тем же значением: второй появился ради
# гейта кружков, который с тех пор заменён. Дубликат имени хуже дубликата литерала — он выглядит
# как осмысленное различие (20.09.2026).
GENERATOR_INPUTS = {'Accent', 'PanelBg', 'DeepBg'}   # их читает themeProjectColors.py, а не ui
orphans = []
for path in sorted(glob.glob(os.path.join(themes_dir, 'vibe*.theme.json'))):
    data = json.load(io.open(path, encoding='utf-8'))
    used = set()

    def collect(node):
        for value in node.values():
            if isinstance(value, dict):
                collect(value)
            elif isinstance(value, str):
                used.add(value)

    collect(data.get('ui', {}))
    dead = [name for name in data.get('colors', {}) if name not in used and name not in GENERATOR_INPUTS]
    if dead:
        orphans.append((os.path.basename(path), dead))
if orphans:
    print('ОШИБКА: запись палитры есть, а пользуется ею никто — мёртвый цвет:')
    for name, dead in orphans:
        print('    %s — %s' % (name, ', '.join(dead)))
    print('  Уберите запись или подставьте её имя туда, где она задумывалась')
    sys.exit(1)
print('  палитра: повторённых безымянных цветов нет, мёртвых записей нет')
PYSWATCH

# 8г. Цвет проекта в шапке окна — из палитры самой темы, а не платформенный.
#
# Платформа красит шапку цветом проекта («Use project colors in main toolbar», включено по
# умолчанию) и берёт его из `RecentProject.ColorN.MainToolbarGradientStart`. Тема, не объявившая
# эти девять ключей, получает платформенные — красный, пурпурный, розовый, — и они спорят с любой
# нашей палитрой (владелец увидел одинаковую пурпурную полосу на графитовой и полуночной, 19.09).
# Значения считает генератор, здесь сверяется, что они не разошлись с палитрой.
"$PYTHON" vibe-plugins/tools/themeProjectColors.py --check || status=1

# 8д. Цвет текста в схеме редактора читается на её же фоне.
#
# «Выглядит плохо» бывает вкусом, а бывает числом. Замер 19.09.2026 нашёл второе: комментарии
# полуночной темы шли на контрасте 2.83 при пороге читаемости 3, графитовой — на 3.49. Глазами
# это «тускло», числом — дефект. Планка 4.0 выбрана по измерению: после починки худшее значение
# по всем схемам 4.14.
"$PYTHON" vibe-plugins/tools/themeContrast.py || status=1

# 8е. Фон окна установки собран для НЫНЕШНЕЙ версии.
#
# Версия нарисована на картинке, а картинка коммитится собранной. Забыть пересобрать её после
# бампа — ошибка на один шаг, и не видно её ниоткуда: файл на месте, гейт дистрибутива сверяет его
# с нашей же копией и зеленеет. Так на образе 0.6.16 оказалось написано 0.6.11 — четыре сборки
# подряд, и заметил это владелец, а не мы.
BG=vibeidea-customization/resources/mac/dmgBackground.tiff
BG_VERSION_FILE="$BG.version"
APP_VERSION=$(sed -n 's/.*full="\([^"]*\)".*/\1/p' vibeidea-customization/resources/idea/VibeIdeaApplicationInfo.xml | head -1)
if [ ! -f "$BG_VERSION_FILE" ]; then
  echo "ОШИБКА: неизвестно, для какой версии собран фон окна установки"
  echo "  Пересоберите: ./vibe-plugins/tools/makeDmgBackground.sh"
  status=1
elif [ "$(cat "$BG_VERSION_FILE")" != "$APP_VERSION" ]; then
  echo "ОШИБКА: на фоне окна установки версия $(cat "$BG_VERSION_FILE"), а собираем $APP_VERSION"
  echo "  Пересоберите: ./vibe-plugins/tools/makeDmgBackground.sh"
  status=1
else
  echo "  фон окна установки: собран для $APP_VERSION"
fi

# 8ж. Тему переключает ПЛАТФОРМЕННЫЙ путь, а не наша пара строк.
#
# `LafManager.setCurrentLookAndFeel` ставит тему и схему редактора — и на этом всё. Переключение
# светлого на тёмное делает `DarculaInstaller` (`JBColor.setDark` + `IconLoader.setUseDarkIcons`),
# а зовёт его только `QuickChangeLookAndFeel.switchLafAndUpdateUI` — тот самый вход, которым
# пользуется платформенная страница оформления. `JBColor.DARK` — кэш, посеянный один раз при
# старте IDE: без этого шага каждая пара `JBColor(светлый, тёмный)` и каждый значок продолжают
# отдавать сторону ПРЕЖНЕЙ темы.
#
# Цена известна: на 0.6.17 переход со светлой темы на графитовую красил окно проекта в тёмное, а
# диалог настроек оставался белым. Ни один тест и ни один гейт этого не видели — цвета из json
# темы применялись правильно, сломан был только шаг, которого у нас не было вовсе.
SWITCH_HELPER=platform/platform-impl/src/com/intellij/ide/actions/QuickChangeLookAndFeel.java
# Строки комментариев отбрасываются: упоминание метода в объяснении — не вызов метода, и гейт,
# падающий на собственном комментарии, учит обходить себя молчанием.
DIRECT_SWITCH=$(grep -rn "setCurrentLookAndFeel" vibe-plugins --include='*.kt' \
  | grep -v '/testSrc/' \
  | grep -vE ':[0-9]+:[[:space:]]*(//|\*)' || true)
if [ -n "$DIRECT_SWITCH" ]; then
  echo "ОШИБКА: тема переключается мимо платформенного пути — диалоги останутся в цветах прежней темы"
  echo "$DIRECT_SWITCH" | sed 's/^/    /'
  echo "  Зовите QuickChangeLookAndFeel.switchLafAndUpdateUI(manager, info, false): он делает"
  echo "  DarculaInstaller.install()/uninstall(), то есть JBColor.setDark и IconLoader.setUseDarkIcons"
  status=1
elif grep -rq "QuickChangeLookAndFeel.switchLafAndUpdateUI" vibe-plugins --include='*.kt'; then
  # Обратная сторона: метод, на который мы опираемся, живёт в платформе и может уехать при синке.
  if ! grep -q "public static void switchLafAndUpdateUI" "$SWITCH_HELPER"; then
    echo "ОШИБКА: платформа больше не отдаёт switchLafAndUpdateUI ($SWITCH_HELPER)"
    echo "  Найдите, чем платформа переключает тему теперь, и переведите страницу «Оформление» на это"
    status=1
  else
    echo "  переключение темы: платформенным путём, DarculaInstaller не обойдён"
  fi
fi

# 9. Идентификаторы панелей: только ASCII и только из VibeToolWindows.
#    Идентификатор уезжает в .idea/workspace.xml и в раскладку окон — русская буква там ломается
#    при смене кодировки, а литерал, написанный руками в пятом файле, однажды разойдётся с XML.
"$PYTHON" - "$root" <<'PYIDS' || status=1
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

# 10. Группы уведомлений и расширения файлов: один факт — одно место.
#     Группа, написанная литералом с опечаткой, не ломает сборку: платформа создаст группу с новым
#     именем, и уведомление уедет мимо настроек, где человек его отключал. Список расширений в
#     plugin.xml и в коде тоже обязан совпадать — иначе тип файла зарегистрируется, а действие не
#     сработает (или наоборот).
"$PYTHON" - "$root" <<'PYFACTS' || status=1
import glob, io, os, re, sys
root = sys.argv[1]
problems = []

declared = set(re.findall(r'"(Vibe [A-Za-z]+)"',
                          io.open(os.path.join(root, 'vibe-plugins/vibe-agent/src/com/vibe/agent/ui/VibeNotifications.kt'),
                                  encoding='utf-8').read()))
used = set()
for path in sorted(glob.glob(os.path.join(root, 'vibe-plugins/*/resources/META-INF/plugin.xml'))):
    text = io.open(path, encoding='utf-8').read()
    for group in re.findall(r'<notificationGroup\b[^>]*id="([^"]+)"', text):
        used.add(group)
        if group not in declared:
            problems.append(f'группа уведомлений {group} не объявлена в VibeNotifications ({os.path.relpath(path, root)})')

for base, _, files in os.walk(os.path.join(root, 'vibe-plugins')):
    if os.sep + 'src' + os.sep not in base + os.sep:
        continue
    for name in files:
        if not name.endswith('.kt') or name == 'VibeNotifications.kt':
            continue
        for line in io.open(os.path.join(base, name), encoding='utf-8'):
            for group in used:
                if f'"{group}"' in line:
                    problems.append(f'{name}: группа уведомлений литералом «{group}» — возьмите VibeNotifications')

# Расширения типа файла: XML против кода.
xml = io.open(os.path.join(root, 'vibe-plugins/vibe-http/resources/META-INF/plugin.xml'), encoding='utf-8').read()
kt = io.open(os.path.join(root, 'vibe-plugins/vibe-http/src/com/vibe/http/HttpFileType.kt'), encoding='utf-8').read()
from_xml = set(re.search(r'extensions="([^"]+)"', xml).group(1).split(';'))
from_kt = set(re.findall(r'"([a-z0-9]+)"', re.search(r'val EXTENSIONS = listOf\(([^)]*)\)', kt).group(1)))
if from_xml != from_kt:
    problems.append(f'расширения .http расходятся: plugin.xml {sorted(from_xml)} против HttpFileType {sorted(from_kt)}')

for problem in problems:
    print('ОШИБКА: ' + problem)
sys.exit(1 if problems else 0)
PYFACTS

[[ $status -eq 0 ]] && echo "UI-гейт: тонкие скроллы на месте (наши панели + правка платформы), обходов VibeScroll нет; значки на месте и различимы"
exit $status
