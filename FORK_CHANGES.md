# FORK_CHANGES.md — реестр отклонений VibeIDEA от апстрима

Документ всех отклонений от `JetBrains/intellij-community`. Обновлять при каждом изменении, затрагивающем платформенный код или состав дистрибутива. Упрощает upstream sync: при конфликтах решения принимаются «по FORK_CHANGES».

## Источник форка

| Remote | URL | Назначение |
|---|---|---|
| `upstream` | https://github.com/JetBrains/intellij-community | база форка; **только fetch** — push отключён физически (`push URL = no_push://upstream-is-fetch-only`) |
| `origin` | — (не создан) | наш репозиторий; появится решением владельца |

## Текущая база апстрима

- Ветка `master` @ `38a8934174` (клон 2026-08-23, shallow `--depth 1`; полная история докачивается фоном — см. roadmap Фаза 0).
- Синк форка вести по **тегам релизов** апстрима вида `idea/2026.2.1` (проверено `git ls-remote --tags`; существуют также `-rc`/`-preview`), не по HEAD master.
- Все теги не тащить (2780 штук тянут релизные ветки): нужный тег точечно — `git fetch upstream tag idea/X.Y.Z --no-tags`.

## Изменённые файлы поверх базы

Точечные правки платформы — см. записи ниже. Добавленные собственные файлы (не конфликтуют при merge):

### Добавлено (наш неймспейс)
- `CLAUDE.md`, `FORK_CHANGES.md` — дисциплина форка. **Причина:** свежий клон продолжает работу без внешнего состояния.
- `docs/vibe/**` — пакет документации форка (свой неймспейс: у апстрима есть собственный `docs/`) (концепт, roadmap, решения, база знаний). **Причина:** та же.
- `.gitmodules` (корень) — submodule `vibe-plugins/vibe-agent/resources/vibeDefaults` → репо VibeBrains (канонический образ `.vibe`, общий с VibeIDE; решение №25). **Причина:** один набор сидов на оба продукта; у апстрима `.gitmodules` нет — merge-конфликтов не даёт. После клона: `git submodule update --init`; URL — https://github.com/VibeBrains/VibeBrains.git.

### platform/platform-impl/src/com/intellij/ui/AppUIUtil.kt
**Причина:** латентный баг апстрима — `loadConsentsForEditing` получает от `ConsentOptions` иммутабельный список, когда у продукта нет бандленных согласий (вендор не JetBrains), и `removeTraceConsents`/`removeIf` падают с `UnsupportedOperationException`; ломает шаг `search_index` (traverseUI) при сборке нашего дистрибутива.
**Изменено:** `var result = options.consents.first` → `…first.toMutableList()` (+ комментарий-маркер `[VibeIDEA]`). Кандидат на отправку апстриму.

### platform/platform-api/src/com/intellij/ui/components/JBScrollBar.java
**Причина:** продуктовое решение владельца 2026-08-28 — тонкие скроллы во ВСЁМ интерфейсе VibeIDEA (дерево проекта, редактор, панели платформы), не только в наших панелях. Другого рычага нет: толщина зашита в конструкторы `ThinScrollBarUI`/`ThinMacScrollBarUI`, а выбор тонкого варианта делает `JBScrollBar.isThin()`, недоступный ни теме, ни настройкам.
**Изменено:** `isThin()` возвращает `vibeScrollBarThickness() > 0` вместо `false`; `createUI` передаёт толщину в тонкие UI; добавлен `vibeScrollBarThickness()` — чтение ключа `vibe.scrollbar.thickness` (дефолт 4, `0` возвращает штатные скроллы платформы). Ключ объявлен в `vibe-agent/plugin.xml` (EP `registryKey`), настройка — Settings → Tools → VibeIDEA → Интерфейс; `registry.properties` платформы не тронут. Комментарии-маркеры `[VibeIDEA]`.
**При синке:** конфликт вероятен только если апстрим сам правит эти два метода — проверять `./vibe-plugins/tools/checkVibeUi.sh`.

### Добавлено для Фазы 2 (языки)
- `vibe-plugins/vibe-lsp/` — плагин `com.vibe.lsp`: vtsls (TS) + Phpactor (PHP) через LSP4IJ (optional depends). **Причина:** плагины PhpStorm/WebStorm закрыты; LSP — лицензионно чистый путь.
- `vibe-plugins/deps/` — пиненная загрузка LSP4IJ 0.20.1 с GitHub releases (sha256), раскладывается в `plugins/lsp4ij/` на сборке.
- `lib/vibe/lsp4ij/` — вендоренный API-jar LSP4IJ (компиляция, scope PROVIDED — в наш плагин не пакуется) + свой `BUILD.bazel`-пакет.
- `.idea/libraries/vibe_lsp4ij.xml`, строки в `.idea/modules.xml` — регистрация модулей (аддитивно).

### Добавлено для Фазы 3 (агентская обвязка)
- `vibe-plugins/vibe-agent/` — плагин `com.vibe.agent`: ACP-клиент (ndjson JSON-RPC/stdio), ToolWindow-чат, permission-гейты, маппинг fs-запросов на Document/VFS. **Причина:** ACP-клиент JetBrains закрыт и в IC отсутствует.

## Зависимости от непубличного API платформы

Платформу здесь мы НЕ правим — но опираемся на её внутренние классы, которые апстрим может
переименовать без предупреждения. Каждая такая зависимость держится под гейтом, иначе синк
ломает поведение молча.

- `com.intellij.ui.components.JBThinOverlappingScrollBar` — тонкие скроллбары во всём нашем UI
  (обёртка `com.vibe.agent.ui.VibeScroll`, решение владельца 2026-08-28). **Риск синка:**
  переименование/удаление класса даёт либо ошибку компиляции, либо — в попапах, где мы обходим
  дерево компонентов, — молчаливый возврат к толстым барам. **Гейт:**
  `./vibe-plugins/tools/checkVibeUi.sh` (проверяет и наличие класса, и что ни один наш скролл
  не создан в обход тонких баров, и что правка `JBScrollBar` на месте).

## Запланированные изменения

- [ ] Свой `README.md` (замена апстримного — identity форка) — Фаза 1.
- [ ] Свой customization-модуль (ресурс `idea/VibeIdeaApplicationInfo.xml`, иконки, splash) — Фаза 1.
- [ ] `VibeIdeaProperties : IdeaCommunityProperties` + свой installers-таргет — Фаза 1.
  - Раскладка `intellij.vibe.agent` тянет модуль `intellij.libraries.zxing.core` (QR-код адреса превью): библиотека едет в дистрибутив только по просьбе раскладки — ни зависимость Bazel, ни `orderEntry` в `.iml` на состав dmg не влияют (разбор — [knowledge/build/threePackagingSystems.md](docs/vibe/knowledge/build/threePackagingSystems.md)).
  - LSP4IJ поставляется через `getAdditionalPluginPaths()`, а НЕ копированием каталога: платформа грузит встроенные плагины только по `plugins/plugin-classpath.txt`, и скопированный мимо индекса плагин не загружается вовсе (разбор — [knowledge/build/bundledPluginIndex.md](docs/vibe/knowledge/build/bundledPluginIndex.md)).
  - `build/BUILD.bazel`: в зависимости модуля сборки добавлен `//platform/build-scripts/licenses` — `VibeIdeaProperties` объявляет лицензии поставляемых языковых серверов, а тип `LibraryLicense` без этой зависимости недоступен. Одна строка в списке deps, конфликтов при мерже не создаёт.
- [ ] Ревизия `bundledPluginModules` (вырезка ненужного — vendor AI, featuresTrainer и т.п.) — Фаза 1.
- [ ] Бандл LSP4IJ + Phpactor + vtsls + DAP-адаптеры — Фаза 2.
- [ ] Плагин агентской обвязки (ACP-клиент, `com.vibe.agent`) — Фаза 3.
- [ ] Погасить апстрим-воркфлоу `.github/workflows/**`, бессмысленные в форке (после создания origin проверить фактом, какие блокируют).

## Инструкция по upstream sync (плейбук)

1. `git fetch upstream --tags`
2. От `next`: ветка `upstream-sync`, `git merge <тег релиза>`.
3. Конфликты: сохранять наши модули (`vibe*`, `com.vibe.*`, `docs/vibe/**`, корневые CLAUDE/FORK_CHANGES), точечные правки платформы — по записям этого файла.
4. Отдельно обновить зеркало: `git checkout master && git merge --ff-only upstream/master` (теги в master не мержатся — релизные теги апстрима не лежат на его master).
5. Валидация: компиляция (bazel) → `./vibe-plugins/tools/checkVibeUi.sh` (тонкие скроллы: класс платформы на месте, обходов нет) → grep-гейт брендинга (после Фазы 1: user-visible «IntelliJ IDEA» в наших ресурсах) → smoke-запуск `./bazel.cmd run //build:idea_community`.
6. Обновить секцию «Текущая база апстрима».

## Пост-merge чистка (повторяемые действия)

Пока пусто — наполняется по факту первых синков.
