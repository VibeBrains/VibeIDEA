# CLAUDE.md — VibeIDEA

> **База — в глобальном `$CLAUDE_CONFIG_DIR/CLAUDE.md`** (действует во всех проектах; оглавление правил — в нём). Здесь — только специфика VibeIDEA.

## Что это

**VibeIDEA** (рабочее имя) — форк [intellij-community](https://github.com/JetBrains/intellij-community): открытая IDE PhpStorm-класса (TypeScript + PHP через LSP) с собственной агентской обвязкой (ACP-клиент). Концепт — [docs/vibe/idea.md](docs/vibe/idea.md), план — [docs/vibe/roadmap.md](docs/vibe/roadmap.md), реестр отличий от апстрима — [FORK_CHANGES.md](FORK_CHANGES.md), журнал решений — [docs/vibe/decisions.md](docs/vibe/decisions.md).

## Git: remotes и ветки

| Remote | Куда | Правило |
|---|---|---|
| `upstream` | JetBrains/intellij-community | **Только fetch. Push в него запрещён всегда.** |
| `origin` | наш репозиторий | пока не создан — появится решением владельца |

- `master` — зеркало `upstream/master`, своих коммитов в него не кладём; обновление только `git fetch upstream && git merge --ff-only upstream/master`.
- `main` — релизная ветка форка (всегда готова к продакшену).
- `next` — повседневная работа (глобальная дисциплина main/next).
- Синк с апстримом — по **тегам релизов** IC, не по HEAD; плейбук в [FORK_CHANGES.md](FORK_CHANGES.md).

## Граница апстрима (фундаментальное правило форка)

- Свой код — **только** в собственных модулях/плагинах с префиксом `vibe` (пакеты `com.vibe.*`). Максимум фич — через extension points, а не патчи платформы.
- Платформенные файлы **запрещено** править ради стиля/линта/формата — merge-налог навсегда. Точечная правка платформы допустима только за реальный баг/проводку фичи/security — и **каждая** фиксируется записью в [FORK_CHANGES.md](FORK_CHANGES.md) (что, где, почему).
- Подробно: [docs/vibe/knowledge/architecture/upstreamBoundary.md](docs/vibe/knowledge/architecture/upstreamBoundary.md).

## Общие сиды `.vibe` — submodule VibeBrains

- `vibe-plugins/vibe-agent/resources/vibeDefaults` — **git submodule** репозитория VibeBrains: канонический образ `.vibe`, общий с VibeIDE (https://github.com/VibeBrains/VibeBrains; локальный клон-спутник: `/Users/borodatych/Projects/VibeCode/VibeBrains`). После свежего клона форка: `git submodule update --init`.
- **Перед любой работой с сидами или сеялкой** (`VibeDefaults`/`VibeDefaultsSeeder`, содержимое `vibeDefaults/`) — сперва подтянуть набор: `git submodule update --remote vibe-plugins/vibe-agent/resources/vibeDefaults` (мог измениться из VibeIDE).
- Правки сидов коммитятся **в VibeBrains** (прямо внутри submodule-каталога), затем в форке коммитится bump указателя. Новый/удалённый файл набора — синхронно правка манифеста `VibeDefaults.MANIFEST`: гейт манифест↔ресурсы (`VibeDefaultsTest.manifestMatchesEmbeddedResourcesExactly`) валит тест при дрейфе.

## Сборка (Bazel)

IC переходит на Bazel (сборка «только средствами IDE» уже не поддерживается); JDK/Bazel вручную не ставятся — тулчейн герметичный (bazelisk + JBR качаются сами). Факты и команды: [docs/vibe/knowledge/build/bazelBuild.md](docs/vibe/knowledge/build/bazelBuild.md).

- Запуск IDE из исходников: `./bazel.cmd run //build:idea_community`
- Инсталляторы VibeIDEA: `./vibeidea-installers.cmd -Dintellij.build.target.os=current -Dintellij.build.target.arch=current` → `out/vibeidea/artifacts/` (нужен клон `android/` — `./getPlugins.sh`). **Соседний `./installers.cmd` — скрипт апстрима**: он собирает чистую IDEA CE без плагинов `vibe-*` в `out/idea-ce`; не удаляем его по правилу границы форка, но и не путаем с нашим.
- Тесты: `./tests.cmd -Dintellij.build.test.patterns=<класс>`

## Стиль нашего UI

- **Скроллы — только тонкие**: любой скролл в нашем UI создаётся через `VibeScroll.pane(...)` / `VibeScroll.thin(...)` (в плагинах без доступа к vibe-agent — платформенным `JBThinOverlappingScrollBar` напрямую). Толщина — общая для всей IDE (Settings → Tools → VibeIDEA → Интерфейс, дефолт 4 px): её читает точечно пропатченный `JBScrollBar` платформы (запись в [FORK_CHANGES.md](FORK_CHANGES.md)). Гейт: `./vibe-plugins/tools/checkVibeUi.sh` — падает на забытом скролле, исчезнувшем классе платформы и потерянной правке. Решение владельца 2026-08-28.
- **Цвета — только токены темы**: `JBColor.namedColor("Vibe.<Область>.<ключ>", дефолт)`; вшитые константы цвета в наших панелях запрещены — тема перекрашивает всё (правило владельца, 2026-08-23). Свои токены объявлять в `vibeNeonDark.theme.json` (секция `Vibe.*`).

## Правила для агентов

Работаете в этом репозитории агентом — читайте [docs/vibe/agentsGuide.md](docs/vibe/agentsGuide.md):
граница форка, язык строк, гейты, инварианты продукта. Корневой `AGENTS.md` — файл апстрима, его
не правим.

## Гейты проекта

Четыре скрипта, все обязательны перед завершением задачи, каждый падает на реальном нарушении:

- `./vibe-plugins/tools/checkVibeUi.sh` — тонкие скроллы (наши панели + правка платформы).
- `./vibe-plugins/tools/checkVibeI18n.sh` — каталог строк: ключ без строки, мёртвый ключ, неизвестный ключ в языке и **храповик** (число русских литералов в коде не может вырасти).
- `./vibe-plugins/tools/checkVibeBranding.sh` — чужой брендинг в наших файлах; исключения в `brandingAllowlist.txt` только с причиной.

- `./vibe-plugins/tools/checkVibeDocs.sh` — документация: запись базы знаний без строки в индексе, битая ссылка индекса, мануал вне дерева, битая относительная ссылка на `.md`.

Пятый — **после сборки инсталлятора**, не перед коммитом: `./vibe-plugins/tools/checkVibeDist.sh`
(готовые плагины вписаны в индекс, библиотеки внутри плагинов, языковые серверы на месте и
запускаются). Юнит-тесты слепы к упаковке по построению — за один день 31.08.2026 четыре дефекта
нашлись только установкой; разбор в [knowledge/build/bundledPluginIndex.md](docs/vibe/knowledge/build/bundledPluginIndex.md).

## Проверка перед завершением задачи

- Затронутые свои модули — компиляция через Bazel (точную команду цели писать в задаче).
- Никогда не запускать тесты при ошибках компиляции.
- Изменение видимой возможности → обновить [docs/vibe/functional.md](docs/vibe/functional.md) в той же задаче; сделанное → пункт `[x]` в [docs/vibe/roadmap.md](docs/vibe/roadmap.md); нетривиальный инсайт → запись в [docs/vibe/knowledge/](docs/vibe/knowledge/README.md) со строкой в индексе.
