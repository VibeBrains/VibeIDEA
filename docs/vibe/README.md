# Документация VibeIDEA (docs/vibe)

Корень навигации: каждый документ достижим отсюда живыми ссылками (ASCII-деревья ссылок не создают — не использовать).

## Карта

- [idea.md](idea.md) — концепт: что строим и почему так.
- [roadmap.md](roadmap.md) — план по фазам; единственный источник правды «что сделано».
- [functional.md](functional.md) — версионно-независимый каталог возможностей продукта.
- [decisions.md](decisions.md) — журнал решений (включая ночные).
- [manuals/skillsSpec.md](manuals/skillsSpec.md) — спека Agent Skills `.vibe/skills/<id>/SKILL.md`: формат, шесть ключей шапки, валидатор, пример (самодостаточная, для LLM).
- [manuals/httpApiSpec.md](manuals/httpApiSpec.md) — спека входящего HTTP API (loopback + Bearer): как дёрнуть агента из CI/бота/крона (самодостаточная, для LLM).
- [manuals/acpSmoke.md](manuals/acpSmoke.md) — ручной чек-лист живого прогона ACP (12 шагов): то, что подделкой агента не проверить.
- [manuals/languageServers.md](manuals/languageServers.md) — как поставить vtsls/Phpactor для TS/PHP.
- [manuals/acpAgentsSpec.md](manuals/acpAgentsSpec.md) — спека реестра ACP-агентов `~/.jetbrains/acp.json` (самодостаточная, для LLM).
- [manuals/providersSpec.md](manuals/providersSpec.md) — спека `providers.json`: свои LLM-провайдеры для прямого чата (самодостаточная, для LLM).
- [manuals/pipelinesSpec.md](manuals/pipelinesSpec.md) — спека `.vibe/pipelines.json`: цепочки шагов-ролей (самодостаточная, для LLM).
- [manuals/serversSpec.md](manuals/serversSpec.md) — спека `.vibe/servers.json`: декларативный дев-стек с волнами и readyCheck (самодостаточная, для LLM).
- [manuals/designSpec.md](manuals/designSpec.md) — спека дизайн-контекста `.vibe/design/` (4 файла; самодостаточная, для LLM).
- [manuals/hooksSpec.md](manuals/hooksSpec.md) — спека `.vibe/hooks.json`: хуки проекта вокруг работы агента (самодостаточная, для LLM).
- [manuals/auditSpec.md](manuals/auditSpec.md) — спека журнала `.vibe/audit.jsonl`: формат записей аудита агента (для парсеров/дашбордов).
- [references/vibeideUxParity.md](references/vibeideUxParity.md) — поведенческие спеки экранов VibeIDE (эталон UX-паритета волн A/B/C).
- [knowledge/README.md](knowledge/README.md) — база знаний: индекс обязателен, запись без строки в индексе не существует.
- [../../FORK_CHANGES.md](../../FORK_CHANGES.md) — реестр отклонений от апстрима + плейбук синка.
- [../../CLAUDE.md](../../CLAUDE.md) — специфика проекта для сессий Claude.

## Конвенции

- Наши доки живут только в `docs/vibe/` (у апстрима свой `docs/` — не трогаем). Имена файлов и папок — camelCase (исключения: README.md и общепринятые верхнеуровневые).
- Мануалы — только в `docs/vibe/manuals/` (папка появится с первым мануалом).
- Фича с форматом/сценарием обязана иметь самодостаточную спеку + пример (проверка: агент в чистом проекте собирает файл, не выходя в интернет).
