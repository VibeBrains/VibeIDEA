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

## Версия протокола: где смотреть и чем она не является

**Номер версии меняется ТОЛЬКО на несовместимых изменениях**, всё непрерывное едет через
capabilities. Поэтому рукопожатие обязано сверяться, и спека говорит это нормативно: клиент
называет свой максимум, агент отвечает своим, и «The client should disconnect, if it doesn't
support this version» плюс «Clients and Agents MUST agree on a protocol version»
(agentclientprotocol.com/protocol/v1/initialization). У нас это делает `AcpClient.agreedVersion`;
молчание агента (поля нет) ошибкой не считается — так отвечали ранние сборки.

**Версия артефакта ≠ версия провода.** README репозитория предупреждает об этом прямо: крейт
`agent-client-protocol` 2.2.0 говорит на протоколе `1`. Судить о совместимости по номеру пакета
нельзя — только по согласованному `protocolVersion`, а внутри версии — по capabilities.

**Где смотреть изменения (три changelog, все с датами, Keep a Changelog):**

| Файл | Про что |
|---|---|
| `CHANGELOG.md` в корне | версии Rust-крейта |
| `schema/v1/CHANGELOG.md` | линия схемы v1 (`schema-v1.*`) |
| `schema/v2/CHANGELOG.md` | черновик v2 (`schema-v2.0.0-alpha.*`) |

На 18.09.2026: `schema-v1.23.0`, `schema-v2.0.0-alpha.5`, крейт 2.2.0. Записи сами помечают
зрелость — `*(unstable)*`, `*(schema)* stabilize …`.

**Нестабильное лежит отдельным файлом**, а не полем: рядом со `schema.json` есть
`schema.unstable.json` (в v1 — 170 стабильных определений против 268), и 41 определение
начинается словами «UNSTABLE … may be removed or changed at any point». Слова `experimental` в
схеме нет вовсе; расширение вне спеки идёт через зарезервированное `_meta`.

**v2 — Draft, а не alpha-ветка**, объявлен 20.07.2026 с прямой оговоркой «Don't ship it by default
in production» и требованием не бросать v1. Наше решение №50 (остаться на v1) источник
подтверждает, а не противоречит ему.

Сверено 20.09.2026 по github.com/agentclientprotocol/agent-client-protocol.
