# Roadmap VibeIDEA

Единственный источник правды «что уже сделано». Маркеры: `[ ]` открыт · `[/]` в работе · `[x]` закрыт (обязательна приписка `— ✅ факт/артефакт`) · `[~]` частично (приписка «что осталось»).

## Фаза 0 — Фундамент (2026-08-23, ночь основания)

- [~] **Клон intellij-community** — рабочее дерево `master @ 38a8934174` (2026-08-23, shallow: полный клон дважды рвался по сети на ~78%, curl 56). Осталось: фоновая дозакачка истории master кусками `--deepen` (см. отчёт ночи основания).
- [x] **Разведка перед стройкой** — ✅ (2026-08-23) 9 агентов: опыт VibeIDE (дисциплина форка, upstream-playbook, доки, база знаний, агентская обвязка) + веб (Bazel-сборка IC, механика брендинга, LSP/лицензии, ACP). Выжимки легли в [knowledge/](knowledge/README.md) и [idea.md](idea.md).
- [x] **Git-дисциплина форка** — ✅ (2026-08-23) remote `upstream` (клоном `--origin upstream`), push в него отключён физически (`no_push://`); ветки `master` (зеркало) / `main` / `next`, работа в `next` — подтверждено `git branch -vv`, `git remote -v`.
- [x] **Docs-пакет** — ✅ (2026-08-23) CLAUDE.md, FORK_CHANGES.md, docs/vibe/{README,idea,roadmap,functional,decisions}.md, docs/vibe/knowledge/ (5 записей + индекс). Свой неймспейс docs/vibe/ — см. решение №11.
- [~] **Проверка сборки из исходников** — ✅ `./bazel.cmd build //build:idea_community`: Build completed successfully, 4009 действий, 37 мин (2026-08-23, лог ночи основания); тулчейн герметичен — JBR/Bazel скачались сами, ничего не ставилось. Осталось: запуск GUI (`run`) — не гонялся ночью намеренно, проверить днём.
- [ ] **AGENTS.md** — правила для AI-агентов (язык строк, база знаний, продуктовые инварианты) — по мере накопления содержимого.
- [ ] **Гейт целостности доков** (dead links / unindexed / unreachable от docs/README.md) — порт `vibe-docs-graph` или Kotlin-аналог + CI.

## Фаза 1 — Свой продукт (брендинг)

- [ ] Customization-модуль: ресурс `idea/VibeIdeaApplicationInfo.xml` (имена, product code, версия), SVG-иконки (обычная/16px/EAP), splash.
- [ ] `VibeIdeaProperties : IdeaCommunityProperties` (по образцу `AndroidStudioProperties` — ~30 строк поверх IC): `platformPrefix`, `baseFileName`, `systemSelector`, кастомайзеры Win/mac/Linux (`bundleIdentifier`, имена папок).
- [ ] Свой installers-таргет (`object VibeIdeaInstallersBuildTarget`) + регистрация в Bazel.
- [ ] Ревизия `bundledPluginModules`: вырезать vendor AI (mcpserver и т.п.), решить судьбу Android-плагина в **нашем** дистрибутиве (для сборки IC он обязателен, для нашего продукта — вероятно нет).
- [ ] Grep-гейт брендинга: user-visible «IntelliJ IDEA»/«JetBrains» в наших ресурсах после каждого синка.

## Фаза 2 — Языки PhpStorm-класса (TS + PHP)

- [ ] Бандл **LSP4IJ** (EPL-2.0) в состав дистрибутива (внешний плагин через `bundleExternalPlugins`).
- [ ] **TypeScript**: vtsls (MIT) — свой плагин-регистратор (языковой маппинг, установка/обновление сервера, Node-детект); подсветка TextMate.
- [ ] **PHP**: Phpactor (MIT) — то же (PHP ≥8.1 детект); подсветка TextMate; Intelephense — опциональная установка с его EULA («премиум-путь»).
- [ ] **Отладка**: vscode-js-debug — готовый шаблон LSP4IJ; vscode-php-debug (Xdebug) — свой DAP-шаблон, кандидат на контрибуцию в LSP4IJ.
- [ ] Отслеживать `platform/lsp` (Apache-2.0) как нативную замену LSP4IJ для языковых фич (без DAP).

## Фаза 3 — Агентская обвязка (плагин `com.vibe.agent`)

- [ ] ACP-клиент на Kotlin SDK `com.agentclientprotocol:acp`: спавн агента, `initialize` / `session/new` / `session/prompt` / `session/update`, permissions.
- [ ] UI: ToolWindow (Swing или JCEF — решить по референсам Continue / jetbrains-cc-gui / ProxyAI).
- [ ] Маппинг ACP→платформа: `fs/read_text_file`→VFS/Document, `fs/write_text_file`→WriteCommandAction + diff-превью (DiffManager), `terminal/*`→Terminal API, async VFS refresh после внешних записей.
- [ ] Совместимость с `~/.jetbrains/acp.json` и ACP Agent Registry; Claude — через `claude-agent-acp`.
- [ ] Перенос контрактов VibeIDE: hooks (0/2/прочее), turn checks (закрытый тип), предохранители, VERIFY-GATE, бюджеты, HTTP API (loopback + Host-check до токена), Config Guard, защита source-папок по всем content roots, Skills (SKILL.md).

## Фаза 4 — Релизная дисциплина

- [ ] Двухфазный поток (Фаза 1 сборка+штамп / Фаза 2 публикация тех же артефактов со сверкой штампа), версия; бейдж версии — в нашем README (свой README заменяет апстримный в Фазе 1, с записью в FORK_CHANGES.md).
- [ ] Слияние ченджлога при отложенной публикации (базлайн = последний опубликованный тег).
- [ ] CI: upstream-lag-check (порог отставания), security-audit (OWASP Dependency-Check), гейт доков.

## Фаза 5 — Синк с апстримом

- [ ] Первый синк по тегу релиза IC по плейбуку FORK_CHANGES.md; наполнить «пост-merge чистку» фактами.
