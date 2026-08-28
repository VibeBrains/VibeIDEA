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
  grep -qE '(^|[^.[:alnum:]])(JBScrollPane|JScrollPane)\(' "$file" || continue
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

[[ $status -eq 0 ]] && echo "UI-гейт: тонкие скроллы на месте (наши панели + правка платформы), обходов VibeScroll нет"
exit $status
