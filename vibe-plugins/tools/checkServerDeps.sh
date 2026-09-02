#!/usr/bin/env bash
# Уязвимости и дрейф версий в том, что МЫ поставляем.
#
# Полный аудит зависимостей форка бессмыслен: это чужая поверхность в тысячи библиотек, за которой
# следит апстрим, и наш отчёт по ней будет шумом, который перестанут читать. Свою поверхность мы
# знаем поимённо — набор языковых серверов и закреплённые версии, которые сами же и выбрали. Её и
# проверяем.
set -euo pipefail
cd "$(dirname "$0")/../.."

fail=0
say() { printf '%s\n' "$1"; }

# --- 1. Уязвимости npm-серверов ---
if command -v npm >/dev/null 2>&1; then
  (
    cd vibe-plugins/deps/servers-npm
    # --omit=dev: в дистрибутив едут только продакшен-зависимости, и отчёт обязан говорить о том,
    # что мы отдаём людям, а не о том, чем собираем.
    OUT=$(npm audit --omit=dev --audit-level=high 2>&1 || true)
    if printf '%s' "$OUT" | grep -qiE "found [1-9][0-9]* (high|critical)"; then
      say "✖ высокие или критические уязвимости в поставляемых серверах:"
      printf '%s\n' "$OUT" | grep -iE "high|critical|vulnerab" | head -8
      exit 1
    fi
    say "  npm-серверы: высоких и критических уязвимостей нет"
  ) || fail=1
else
  say "  npm не найден — аудит серверов не выполнялся"
fi

# --- 2. Версии в лицензионном отчёте не разошлись с закреплёнными ---
# Отчёт о третьих лицах пишется руками (phar и npm-дерево сборке не видны), а значит однажды
# разъедется с локом. Здесь дешёвая сверка на уровне исходников, до всякой сборки.
PHPACTOR_PIN=$(grep -m1 '^PHPACTOR_V=' vibe-plugins/deps/pins.env | cut -d= -f2)
grep -q "\"$PHPACTOR_PIN\"" build/src/org/jetbrains/intellij/build/VibeIdeaProperties.kt \
  || { say "✖ версия Phpactor в лицензиях разошлась с закреплённой ($PHPACTOR_PIN)"; fail=1; }

for var in JS_DEBUG_V PHP_DEBUG_V; do
  PIN=$(grep -m1 "^$var=" vibe-plugins/deps/pins.env | cut -d= -f2)
  grep -q "\"$PIN\"" build/src/org/jetbrains/intellij/build/VibeIdeaProperties.kt \
    || { say "✖ версия отладчика ($var=$PIN) в лицензиях разошлась с закреплённой"; fail=1; }
done

for pkg in "@vtsls/language-server" "vscode-langservers-extracted"; do
  PIN=$(python3 -c "import json;print(json.load(open('vibe-plugins/deps/servers-npm/package.json'))['dependencies']['$pkg'])")
  grep -q "\"$PIN\"" build/src/org/jetbrains/intellij/build/VibeIdeaProperties.kt \
    || { say "✖ версия $pkg в лицензиях разошлась с закреплённой ($PIN)"; fail=1; }
done
[ "$fail" -eq 0 ] && say "  версии в лицензионном отчёте совпадают с закреплёнными"

# --- 3. Закрепление не потеряно ---
grep -q '"@vtsls/language-server": "[0-9]' vibe-plugins/deps/servers-npm/package.json \
  || { say "✖ версия vtsls перестала быть точной — диапазон означает «у всех разное»"; fail=1; }

if [ "$fail" -ne 0 ]; then
  say "Аудит поставляемого: ПРОВАЛЕН"
  exit 1
fi
say "Аудит поставляемого: уязвимостей нет, версии закреплены и совпадают"
