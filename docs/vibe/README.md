# Документация VibeIDEA (docs/vibe)

Корень навигации: каждый документ достижим отсюда живыми ссылками (ASCII-деревья ссылок не создают — не использовать).

## Карта

- [idea.md](idea.md) — концепт: что строим и почему так.
- [roadmap.md](roadmap.md) — план по фазам; единственный источник правды «что сделано».
- [functional.md](functional.md) — версионно-независимый каталог возможностей продукта.
- [parityVibeIde.md](parityVibeIde.md) — сверка с VibeIDE: что уже умеем, чего не хватает и что переносить не нужно.
- [decisions.md](decisions.md) — журнал решений (включая ночные).
- [manuals/langFileSpec.md](manuals/langFileSpec.md) — спека языкового файла `~/.vibe/lang/<код>.json`: формат, подстановки, поведение при запуске (самодостаточная, для LLM).
- [manuals/codeGraphSpec.md](manuals/codeGraphSpec.md) — спека графа проекта `.vibe/codeGraph.json`: узлы, связи с происхождением, инкрементальность, примеры jq (для агентов и парсеров).
- [manuals/agentRunsSpec.md](manuals/agentRunsSpec.md) — спека журнала прогонов `.vibe/agent-runs.jsonl`: формат, правило сиротства, усечение (для парсеров/дашбордов).
- [manuals/projectContextSpec.md](manuals/projectContextSpec.md) — что агент обязан читать и чего не должен касаться: `.cursor/rules`, `.vibe/ignore`, папки-справочники, таблица доступа (самодостаточная, для LLM).
- [manuals/release.md](manuals/release.md) — как выпустить релиз: ветка, сборка с гейтом дистрибутива, заметки, тег, публикация, права.
- [manuals/compareModels.md](manuals/compareModels.md) — как сравнить две модели на своём репозитории: почему не по бенчмаркам, порядок прогонов, что считать кроме метрики.
- [manuals/debugging.md](manuals/debugging.md) — отладка TS и PHP: адаптеры ставятся снаружи, проверка доктором, подключение через LSP4IJ.
- [manuals/telegramBridge.md](manuals/telegramBridge.md) — мост в Telegram: свой бот, разрешение чата владельцем, команды, прокси и чего пока нет.
- [manuals/commandsSpec.md](manuals/commandsSpec.md) — спека команд проекта `.vibe/commands.json`: формат, что отклоняется и почему, разрешение по хешу, секреты по имени (самодостаточная, для LLM).
- [manuals/skillsSpec.md](manuals/skillsSpec.md) — спека Agent Skills `.vibe/skills/<id>/SKILL.md`: формат, шесть ключей шапки, валидатор, пример (самодостаточная, для LLM).
- [manuals/httpApiSpec.md](manuals/httpApiSpec.md) — спека входящего HTTP API (loopback + Bearer): как дёрнуть агента из CI/бота/крона (самодостаточная, для LLM).
- [manuals/acpSmoke.md](manuals/acpSmoke.md) — ручной чек-лист живого прогона ACP (12 шагов): то, что подделкой агента не проверить.
- [manuals/languageServers.md](manuals/languageServers.md) — как поставить vtsls/Phpactor для TS/PHP.
- [manuals/acpAgentsSpec.md](manuals/acpAgentsSpec.md) — спека реестра ACP-агентов `~/.jetbrains/acp.json` (самодостаточная, для LLM).
- [manuals/providersSpec.md](manuals/providersSpec.md) — спека `providers.json`: свои LLM-провайдеры для прямого чата (самодостаточная, для LLM).
- [manuals/pipelinesSpec.md](manuals/pipelinesSpec.md) — спека `.vibe/pipelines.json`: цепочки шагов-ролей (самодостаточная, для LLM).
- [manuals/serversSpec.md](manuals/serversSpec.md) — спека `.vibe/servers.json`: декларативный дев-стек с волнами и readyCheck (самодостаточная, для LLM).
- [manuals/modelQuirksSpec.md](manuals/modelQuirksSpec.md) — спека `.vibe/modelQuirks.json`: что конкретная модель отказывается принимать (самодостаточная, для LLM).
- [manuals/designSpec.md](manuals/designSpec.md) — спека дизайн-контекста `.vibe/design/` (4 файла; самодостаточная, для LLM).
- [manuals/hooksSpec.md](manuals/hooksSpec.md) — спека `.vibe/hooks.json`: хуки проекта вокруг работы агента (самодостаточная, для LLM).
- [manuals/auditSpec.md](manuals/auditSpec.md) — спека журнала `.vibe/audit.jsonl`: формат записей аудита агента (для парсеров/дашбордов).
- [releaseDonationPhrases.md](releaseDonationPhrases.md) — фразы блока «Поддержать проект» в релизах: пул, отложенные, история использования.
- [references/releaseNotes-v0.1.0.md](references/releaseNotes-v0.1.0.md) — заметки первого релиза: образец оформления, на который равняются следующие.
- [references/digest20260831.md](references/digest20260831.md) — разбор внешнего дайджеста 31.08.2026: фолбэк при отзыве доступа к модели, подстановка секретов, навыки как пакет, режимы отказа агентов, сравнение моделей; бэклог с приоритетами.
- [references/digest20260901.md](references/digest20260901.md) — разбор внешнего дайджеста 01.09.2026: формат API на уровне модели (OpenCode Go), гранулярность причуд (MiniMax), атрибуция действий в журнале (NIST), защита журнала от инструментов агента, Claude Agent SDK и лимиты подписки; бэклог с приоритетами.
- [references/vibeideUxParity.md](references/vibeideUxParity.md) — поведенческие спеки экранов VibeIDE (эталон UX-паритета волн A/B/C).
- [knowledge/README.md](knowledge/README.md) — база знаний: индекс обязателен, запись без строки в индексе не существует.
- [../../FORK_CHANGES.md](../../FORK_CHANGES.md) — реестр отклонений от апстрима + плейбук синка.
- [../../CLAUDE.md](../../CLAUDE.md) — специфика проекта для сессий Claude.

## Конвенции

- Наши доки живут только в `docs/vibe/` (у апстрима свой `docs/` — не трогаем). Имена файлов и папок — camelCase (исключения: README.md и общепринятые верхнеуровневые).
- Мануалы — только в `docs/vibe/manuals/` (папка появится с первым мануалом).
- Фича с форматом/сценарием обязана иметь самодостаточную спеку + пример (проверка: агент в чистом проекте собирает файл, не выходя в интернет).
