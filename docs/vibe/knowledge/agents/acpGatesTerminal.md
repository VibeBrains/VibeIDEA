# Перенос гейтов и терминала в ACP-модель (VibeIDEA)

← [Индекс базы знаний](../README.md)

## Суть проблемы

В VibeIDE весь цикл агента живёт **в клиенте**, поэтому хуки, VERIFY-GATE, turn-checks и anti-loop вшиты прямо в `chatThreadService`. В VibeIDEA цикл живёт **в процессе Claude Code**, и клиент (плагин `vibe-agent`) видит агента только через поток `session/update` + запросы `session/request_permission` и `fs/*`. Это меняет, где и как каждый механизм можно зацепить.

## Ключевые решения (2026-08-27)

- **Реестр tool-call'ов — предпосылка всего.** `session/update` даёт `tool_call` (анонс) и `tool_call_update` (статусы) по `toolCallId`. Раньше панель их игнорировала. `ToolCallRegistry` собирает вызовы по id; на нём стоят аудит, пост-хуки, терминальный стрим. Без него ни одна из четырёх фич не реализуема.

- **Терминал агента — это ДВЕ независимые вещи.** Дефолтный `claude-agent-acp` НЕ вызывает стандартные ACP `terminal/*` — он исполняет Bash внутри себя и отдаёт вывод расширением `_meta.terminal_output` в `tool_call_update` (гейт — `clientCapabilities._meta.terminal_output`). Поэтому: (а) для Claude — только объявить `_meta.terminal_output` и рендерить стрим (никакого своего исполнения); (б) стандартные `terminal/create|output|wait_for_exit|kill|release` + capability `terminal` — отдельная инвестиция под сторонних агентов (Gemini CLI и др.), для Claude она мертва. Проверять при обновлении адаптера: факт снят с dist-сборки 0.70.0.

- **`terminal/wait_for_exit` блокирует — уводить с reader-потока.** Ответ на этот запрос приходит, когда процесс завершится. Если обрабатывать на единственном reader-потоке `AcpClient`, он заморозит весь входящий поток. Решение: `respond()` для `terminal/wait_for_exit` запускает обработчик в отдельном потоке и шлёт ответ асинхронно.

- **preToolUse блокирует только в точках клиента.** Заблокировать инструмент (exit 2) можно лишь там, где клиент участвует: `session/request_permission` (есть `rawInput`) и `fs/write_text_file`. Инструменты, которые Claude гоняет внутри (его Bash/правки), обходят блокировку, если режим разрешений не заставляет спрашивать. Это зафиксировано в спеке — не баг, а граница модели. `postToolUse`/`turnEnd` наблюдаемы полностью (по `tool_call_update` completed/failed и по резолву `session/prompt`).

- **VERIFY-GATE и turn-checks цепляются за конец `session/prompt`.** В ACP нет `vibe_complete`, поэтому «конец хода» — это резолв `session/prompt` со `stopReason=end_turn`. Bounce (возврат агента на доработку) = повторный `session/prompt` синтетическим сообщением; счётчики попыток — клиентские, в `AgentPanel.promptAcpTurn` (рекурсивный цикл). Побочно это **устраняет дефект VibeIDE**, где гейт срабатывал только на ветке `vibe_complete` и обходился завершением прозой.

- **Гейты гонять НЕ на reader-потоке.** `evaluateGates` читает файлы и запускает команду сборки (может быть минуты) — диспатчится на pooled-поток из `whenComplete`, иначе блокирует обработку сообщений.

- **Предохранители переживают рестарт намеренно.** `VibeBreakerService` — project-level `PersistentStateComponent` в workspace-файле. Security-находка (утечка секрета / защищённый путь) взводит залипающий предохранитель, который блокирует старт следующего хода до ручного снятия с подтверждением. Пер-итерационные предохранители VibeIDE (anti-loop по сигнатуре, лимиты итераций) НЕ переносимы: внутренние итерации Claude Code клиенту не видны, и у CC свой anti-loop.

- **Приватность аудита — жёсткий контракт.** Аргументы, тела команд, поисковые запросы, содержимое файлов не пишутся никогда; только имя инструмента и целевой путь (≤260), у командных инструментов — даже пути нет. Порт `toolCallAudit.ts`.

## Файлы

- `acp/ToolCallRegistry.kt`, `acp/AcpClient.kt` (capability + `terminal/*` + async wait).
- `terminal/AgentTerminalService.kt`, `terminal/TerminalOutputBuffer.kt`, `ui/TerminalConsole.kt`.
- `guard/ShellSafetyAnalyzer.kt` (порт `nlShellSafetyAnalyzer.ts`).
- `hooks/HookConfig.kt`, `hooks/HookOutcome.kt`, `hooks/HookRunner.kt`.
- `gates/VerifyGatePolicy.kt`, `gates/VerifyGateRunner.kt`, `gates/TurnChecks.kt`, `gates/VibeBreakerService.kt`.
- `audit/AuditEvent.kt`, `audit/AuditLog.kt`, `audit/ToolCallAudit.kt`.
- Проводка и bounce-цикл — `ui/AgentPanel.kt`; настройки — `settings/VibeAgentSettings.kt` + `VibeAgentConfigurable.kt`.
