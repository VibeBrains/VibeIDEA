# VibeIDEA — концепт

> Рабочее имя. Риск: сходство с товарным знаком «IntelliJ IDEA» (JetBrains) — окончательное имя выбирает владелец. См. [decisions.md](decisions.md).

## Суть

Открытая IDE **PhpStorm-класса** на платформе intellij-community — TypeScript + PHP как первоклассные языки — с собственной **агентской обвязкой**: перенос идеологии VibeIDE («Ты видишь всё — и управляешь всем») с форка VS Code на платформу IntelliJ.

Три кита:

1. **Платформа intellij-community** (Apache-2.0): зрелый редактор, PSI, отладчик, VCS, терминал, инспекции — всё, за что любят JetBrains IDE.
2. **Языки через LSP** — потому что PHP-плагин PhpStorm и JS/TS-плагин WebStorm **проприетарны** и в IC их нет, а открытых аналогов сопоставимого уровня не существует. Стек (лицензионно чистый для бандла):
   - LSP/DAP-клиент: **LSP4IJ** (Red Hat, EPL-2.0) — зрелый, 600k+ установок, DAP в комплекте;
   - PHP: **Phpactor** (MIT); Intelephense — только опциональной установкой пользователем (EULA запрещает редистрибуцию);
   - TypeScript: **vtsls** (MIT, обёртка над VSCode TS extension), fallback typescript-language-server (Apache-2.0), в перспективе tsgo;
   - Отладка: **vscode-js-debug** (MIT, шаблон уже в LSP4IJ) и **vscode-php-debug** (MIT, Xdebug) — свой DAP-шаблон;
   - Параллельно следим за открытым `platform/lsp` (Apache-2.0, открыт JetBrains в июне 2026) — кандидат на нативную миграцию языковых фич (DAP он не даёт).
3. **Агентская обвязка как ACP-клиент**: клиент Agent Client Protocol (JSON-RPC 2.0/stdio) на открытом Kotlin SDK (`com.agentclientprotocol:acp`, Apache-2.0) — сразу весь парк агентов (Claude через `claude-agent-acp`, Codex, Gemini CLI, 60+ в registry). Реализация JetBrains (AI Chat) закрыта и в IC отсутствует — свой клиент неизбежен и стратегически правильный.

## Что переносим из VibeIDE (проверенные контракты, не код)

- **Протокольный слой `.vibe/`**: hooks.json (коды выхода 0/2/прочее), servers.json (декларативный дев-стек), agents.json, commands.json, providers.json — принципы «отсутствие файла = дефолт», «битая запись не роняет реестр», спека+пример для каждого формата.
- **Слой инструментов**: пагинация и лимиты у всех читающих тулов, edit-safety (must-read-first, pre-apply verification — на Document/VFS ложится идеально), anti-shell contract, фоновые команды.
- **Гейты агента**: VERIFY-GATE, детерминированные turn checks (закрытый тип, без LLM-судьи), предохранители-залипания, детектор петель, бюджеты ролей.
- **Безопасность**: Host-проверка на loopback (DNS rebinding — для IntelliJ с его built-in server особенно горячо), Config Guard для машинных конфигов проекта, защита source-папок во всех content roots, allowlist>denylist, защита от отравления контекста.
- **HTTP API**: loopback-only, Bearer из защищённого хранилища (PasswordSafe), контракт переносится дословно.
- Полный перенос-лист — в [roadmap.md](roadmap.md), уроки — в [knowledge/](knowledge/README.md).

## Чем это лучше VibeIDE (почему IntelliJ-платформа)

- Вырезание вендорных AI-фич — **исключением плагинов из дистрибутива**, а не патчами workbench: архитектурно чище, меньше merge-налог.
- `code_graph` дешевле строить поверх PSI/UAST, чем своим индексом.
- Свой код — плагины в отдельных модулях: оверлей-принцип VibeIDE здесь родной.

## Ключевые ограничения (зафиксированы фактами разведки 2026-08-23)

- PhpStorm/WebStorm-плагины закрыты; уровень «PhpStorm Premium» из открытых компонентов недостижим 1:1 — Phpactor слабее Intelephense Premium по «умности». Честная планка: «полноценная работа с PHP/TS», не «клон PhpStorm».
- Сборка тяжёлая: Bazel, ~100 GB диска на полный цикл, 16 GB RAM — впритык (CI добавляет 30 GB swap).
- Товарный знак в имени — решить до публикации.

## Риски

| Риск | Митигация |
|---|---|
| Темп апстрима IC (высокочастотный master) | синк по тегам релизов, оверлей-принцип, FORK_CHANGES.md |
| Intelephense EULA | не бандлим; опциональная установка пользователем с его EULA |
| JCEF в собственной сборке | JBR с JCEF под контролем нашего build-pipeline |
| Закрытость ACP-клиента JetBrains | свой клиент на открытом Kotlin SDK; спека и SDK — Apache-2.0 |
