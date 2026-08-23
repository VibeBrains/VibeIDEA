# Агентская обвязка: свой ACP-клиент
← [Knowledge Index](../README.md)

## [архитектура] ACP-клиент вместо привязки к одному агенту

**Контекст:** разведка 2026-08-23. В JetBrains IDE агенты живут в AI Chat (ACP-клиент встроен с 2025.3; JetBrains — ко-мейнтейнер ACP с окт-2025), но **клиентская реализация закрыта и в intellij-community отсутствует** — в форке свой клиент неизбежен.

**Суть:**
- ACP = JSON-RPC 2.0 поверх stdio («LSP для агентов»): `initialize` / `session/new` / `session/prompt` / `session/update`, permission-gated tool calls, терминал со стороны клиента, diff-просмотр. 60+ агентов в registry (обновляется ежечасно), конфиг кастомных агентов — `~/.jetbrains/acp.json`.
- Открыто (Apache-2.0): спека + SDK на 5 языках, включая **Kotlin** — `com.agentclientprotocol:acp` в Maven Central (KMP/JVM, клиент+агент, stdio). Claude — через официальный адаптер `@agentclientprotocol/claude-agent-acp` (Apache-2.0). Официальный Claude Code JetBrains-плагин закрыт (терминальная архитектура: CLI в терминале + локальный MCP-сервер `ide` по WebSocket).
- Референсы с открытым кодом: **Continue** (Apache-2.0; тонкий Kotlin-слой + core-процесс Node + JCEF-webview, связь JSON по stdio/TCP), **ProxyAI** (Apache-2.0; чисто JVM/Kotlin без внешнего core, Auto Apply — стриминг правок с diff-превью), **jetbrains-cc-gui** (MIT; Kotlin + JCEF).
- Точки расширения платформы: `com.intellij.toolWindow`, JCEF (`JBCefBrowser`; нужен JBR с JCEF — в своей сборке под контролем), `WriteCommandAction`+`DiffManager` (правки/диффы), VFS refresh после записей CLI мимо IDE (`BulkFileListener`, `asyncRefresh`), Terminal API, `InlineCompletionProvider`, `GeneralCommandLine`/pty4j.
- Ограничение ACP: пока только локальные подпроцессы (remote-транспорт в разработке).

**Применение:** плагин `com.vibe.agent` строить клиентом ACP на Kotlin SDK; маппинг: `fs/read_text_file`→VFS/Document, `fs/write_text_file`→WriteCommandAction + diff-превью, `session/request_permission`→подтверждения UI, `terminal/*`→Terminal API. Совместимость с `~/.jetbrains/acp.json` и registry — бесплатная экосистема. Поверх — контракты VibeIDE: hooks (0/2/прочее), детерминированные turn checks, предохранители, Host-check на loopback-слушателях.

**Антипаттерны:** реверс/встраивание закрытого Claude-плагина (лицензия Marketplace); свой протокол вместо ACP (изоляция от экосистемы); правки файлов агентом без VFS refresh (рассинхрон Document/диск).
