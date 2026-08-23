# PHP/TS в открытом дистрибутиве: LSP-стек и лицензии
← [Knowledge Index](../README.md)

## [архитектура] Единственный лицензионно чистый путь к PhpStorm-классу

**Контекст:** разведка 2026-08-23. Цель продукта — TS+PHP уровня PhpStorm, но PHP-плагин (PhpStorm) и JavaScript/TypeScript-плагин (WebStorm) проприетарны, «incompatible with IntelliJ IDEA open source builds» (Marketplace, plugin 6610); открытых аналогов сопоставимого уровня не существует. Лицензии проверялись по первоисточникам (тексты LICENSE/EULA).

**Суть:**
- **LSP-клиент:** LSP4IJ (Red Hat) — EPL-2.0 (weak copyleft на сам плагин, наши модули не заражает; редистрибуция разрешена), зрелый (600k+ установок, 50+ зависимых плагинов), включает **DAP-клиент** (отладка). Шаблон для typescript-language-server есть из коробки; PHP-шаблона нет — регистрируется своим плагином.
- **JetBrains `platform/lsp`:** открыт в июне 2026 (Apache-2.0, в master IC) — кандидат на нативную миграцию языковых фич; **DAP/отладки не содержит** — LSP4IJ остаётся минимум ради отладки.
- **PHP:** Phpactor — MIT, активен, полностью свободный; слабее Intelephense Premium по «умности». **Intelephense — бандлить НЕЛЬЗЯ**: EULA §5(c) прямо запрещает «reproduce, copy, distribute, resell»; легальный паттерн — установка пользователем с принятием его EULA (так делают Helix/Sublime/Emacs). Прецедент нашей схемы: плагин «PHP LSP» (id 31223) поверх LSP4IJ с бандленным бинарём.
- **TypeScript:** vtsls — MIT, обёртка над официальным VSCode TS extension (фичи почти уровня VSCode, дефолт в Zed); fallback — typescript-language-server (Apache-2.0); стратегически — tsgo/`@typescript/native-preview` (Apache-2.0, встроенный нативный LSP) как будущий официальный сервер.
- **Отладка:** vscode-js-debug (MIT) — шаблон уже в LSP4IJ; vscode-php-debug (MIT, официально под эгидой Xdebug) — стандартный stdio DAP-адаптер, шаблона в LSP4IJ нет, связка публично не проверена — писать свой шаблон (кандидат на контрибуцию в LSP4IJ).
- **Рантаймы на машине пользователя:** Node.js (vtsls/tsls/js-debug/php-debug), PHP ≥8.1 (Phpactor) — нужен детект и внятная диагностика отсутствия.

**Применение:** бандлим LSP4IJ + Phpactor + vtsls + оба DAP-адаптера (всё MIT/Apache-2.0/EPL-2.0 — совместимо с Apache-2.0 дистрибутива); Intelephense — опциональный «премиум-путь» установкой пользователя; следим за `platform/lsp` и tsgo. Честная планка в коммуникации: «полноценные PHP/TS», не «клон PhpStorm» — Premium-фичи Intelephense (rename, code actions, inlay hints…) в бесплатном tier отсутствуют.

**Антипаттерны:** бандлить Intelephense «пока никто не заметил»; обещать паритет с PhpStorm; вшивать выбор сервера без возможности переключения (tsgo на подходе).
