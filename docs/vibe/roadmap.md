# Roadmap VibeIDEA

Единственный источник правды «что уже сделано». Маркеры: `[ ]` открыт · `[/]` в работе · `[x]` закрыт (обязательна приписка `— ✅ факт/артефакт`) · `[~]` частично (приписка «что осталось»).

## Фаза 0 — Фундамент (2026-08-23, ночь основания)

- [~] **Клон intellij-community** — рабочее дерево `master @ 38a8934174` (2026-08-23, shallow: полный клон дважды рвался по сети на ~78%, curl 56). Осталось: фоновая дозакачка истории master кусками `--deepen` (см. отчёт ночи основания).
- [x] **Разведка перед стройкой** — ✅ (2026-08-23) 9 агентов: опыт VibeIDE (дисциплина форка, upstream-playbook, доки, база знаний, агентская обвязка) + веб (Bazel-сборка IC, механика брендинга, LSP/лицензии, ACP). Выжимки легли в [knowledge/](knowledge/README.md) и [idea.md](idea.md).
- [x] **Git-дисциплина форка** — ✅ (2026-08-23) remote `upstream` (клоном `--origin upstream`), push в него отключён физически (`no_push://`); ветки `master` (зеркало) / `main` / `next`, работа в `next` — подтверждено `git branch -vv`, `git remote -v`.
- [x] **Docs-пакет** — ✅ (2026-08-23) CLAUDE.md, FORK_CHANGES.md, docs/vibe/{README,idea,roadmap,functional,decisions}.md, docs/vibe/knowledge/ (5 записей + индекс). Свой неймспейс docs/vibe/ — см. решение №11.
- [~] **Проверка сборки из исходников** — ✅ `./bazel.cmd build //build:idea_community`: Build completed successfully, 4009 действий, 37 мин (2026-08-23, лог ночи основания); тулчейн герметичен — JBR/Bazel скачались сами, ничего не ставилось. Осталось: запуск GUI (`run`) — не гонялся ночью намеренно, проверить днём.
- [x] **Инсталлятор из исходников (dmg)** — ✅ (2026-08-23) `./installers.cmd -Dintellij.build.target.os=current -Dintellij.build.target.arch=current` + skip `sources_archive,cross_platform_dist,non_bundled_plugins`: `out/idea-ce/artifacts/ideaIC-263.SNAPSHOT-aarch64.dmg` (783M, `hdiutil verify` VALID, sha256 OK), ~5 мин на тёплом bazel-кэше. Не подписан — Gatekeeper «Open Anyway». Внутри ванильный «IntelliJ IDEA OSS.app» — наш брендинг это Фаза 1.
- [ ] **AGENTS.md** — правила для AI-агентов (язык строк, база знаний, продуктовые инварианты) — по мере накопления содержимого.
- [ ] **Гейт целостности доков** (dead links / unindexed / unreachable от docs/README.md) — порт `vibe-docs-graph` или Kotlin-аналог + CI.

## Фаза 1 — Свой продукт (брендинг)

- [x] Customization-модуль — ✅ (2026-08-23) `vibeidea-customization/`: `idea/VibeIdeaApplicationInfo.xml` (product=VibeIDEA, код `VI`), свои SVG-иконки (оригинальная V-геометрия, без знаков JetBrains); splash отключён (`useSplash=false`).
- [x] `VibeIdeaProperties : IdeaCommunityProperties` — ✅ (2026-08-23) `build/src/org/jetbrains/intellij/build/VibeIdeaProperties.kt`: prefix `VibeIdea`, selector `VibeIdea2026.3`, mac `com.vibe.vibeidea`/`VibeIDEA.app`, вырезаны `intellij.mcpserver.plugin` и `intellij.featuresTrainer`. Корневой дескриптор `META-INF/VibeIdeaPlugin.xml` — зеркало IdeaPlugin.xml.
- [x] Свой installers-таргет — ✅ (2026-08-23) `build/src/VibeIdeaInstallersBuildTarget.kt` + `//build:vibeidea_installers` + обёртка `vibeidea-installers.cmd`. Гейт фазы: `out/vibeidea/artifacts/vibeIdea-263.SNAPSHOT-aarch64.dmg` (778M, hdiutil VALID, product-info: name=VibeIDEA, code=VI, `VibeIDEA.app`).
- [~] Ревизия `bundledPluginModules` — ✅ mcpserver/featuresTrainer вырезаны (2026-08-23). Осталось: судьба Android-плагина в нашем дистрибутиве; полная ревизия списка.
- [ ] Grep-гейт брендинга: user-visible «IntelliJ IDEA»/«JetBrains» в наших ресурсах после каждого синка.

- [ ] Свой `.icns` для macOS (сейчас в `.app` наследуется иконка IC — знак JetBrains; до публикации обязательно заменить).

## Фаза 2 — Языки PhpStorm-класса (TS + PHP)

- [x] Бандл **LSP4IJ** (EPL-2.0) — ✅ (2026-08-23) `bundleExternalPlugins` в VibeIdeaProperties раскладывает пиненный релиз с GitHub (`vibe-plugins/deps/download.sh`, sha256) в `plugins/lsp4ij/`; проверено содержимым .sit.
- [~] **TypeScript**: vtsls — ✅ плагин `intellij.vibe.lsp` (`com.vibe.lsp`): server+fileNamePatternMapping через LSP4IJ (optional depends, PROVIDED-библиотека `lib/vibe/lsp4ij/`), резолв бинаря с учётом GUI-PATH macOS; подсветка — TextMate из комплекта IC; `plugins/vibe-lsp/lib/vibe-lsp.jar` в дистрибутиве. Осталось: авто-установка сервера, настройки путей, живой прогон на реальном проекте.
- [~] **PHP**: Phpactor — ✅ в том же плагине (server+mapping `*.php`); Intelephense — задокументированный путь самостоятельной установки ([мануал](manuals/languageServers.md)). Осталось: то же, что для TS.
- [ ] **Отладка**: vscode-js-debug — готовый шаблон LSP4IJ; vscode-php-debug (Xdebug) — свой DAP-шаблон, кандидат на контрибуцию в LSP4IJ.
- [ ] Отслеживать `platform/lsp` (Apache-2.0) как нативную замену LSP4IJ для языковых фич (без DAP).

## Фаза 3 — Агентская обвязка (плагин `com.vibe.agent`)

- [~] ACP-клиент — ✅ (2026-08-23) `vibe-plugins/vibe-agent/` (`com.vibe.agent`): собственный минимальный клиент (ndjson JSON-RPC 2.0/stdio, ~250 строк, без внешнего SDK — kotlinx-serialization JsonElement без компилятор-плагина): `initialize`/`session/new`/`session/prompt`/`session/update`, permission-диалог (закрыт = отказ), спавн без оболочки, PATH-фикс для GUI macOS. `plugins/vibe-agent/lib/vibe-agent.jar` в дистрибутиве. Осталось: живой прогон с реальным агентом; миграция на официальный Kotlin SDK — осознанная развилка (см. decisions №14).
- [~] UI — ✅ ToolWindow «Vibe Agent» (Swing, путь ProxyAI): выбор агента, стриминг-транскрипт, ввод, стоп. Осталось: rich-рендер (markdown, диффы), JCEF-решение по мере роста.
- [~] Маппинг ACP→платформа — ✅ `fs/read_text_file` видит несохранённые правки (Document-first), `fs/write_text_file` — WriteCommandAction для открытых файлов, NIO+async VFS refresh для остальных. Осталось: diff-превью перед записью (DiffManager), `terminal/*`→Terminal API.
- [x] Совместимость с `~/.jetbrains/acp.json` — ✅ (2026-08-23) толерантный парсер (битая запись пропускается), дефолт — Claude Code через `npx @agentclientprotocol/claude-agent-acp`; спека формата: [manuals/acpAgentsSpec.md](manuals/acpAgentsSpec.md).
- [ ] Перенос контрактов VibeIDE: hooks (0/2/прочее), turn checks (закрытый тип), предохранители, VERIFY-GATE, бюджеты, HTTP API (loopback + Host-check до токена), Config Guard, защита source-папок по всем content roots, Skills (SKILL.md).

## Фаза 4 — Релизная дисциплина

- [ ] Двухфазный поток (Фаза 1 сборка+штамп / Фаза 2 публикация тех же артефактов со сверкой штампа), версия; бейдж версии — в нашем README (свой README заменяет апстримный в Фазе 1, с записью в FORK_CHANGES.md).
- [ ] Слияние ченджлога при отложенной публикации (базлайн = последний опубликованный тег).
- [ ] CI: upstream-lag-check (порог отставания), security-audit (OWASP Dependency-Check), гейт доков.

## Фаза 5 — Синк с апстримом

- [ ] Первый синк по тегу релиза IC по плейбуку FORK_CHANGES.md; наполнить «пост-merge чистку» фактами.
