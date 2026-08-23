# Документация VibeIDEA (docs/vibe)

Корень навигации: каждый документ достижим отсюда живыми ссылками (ASCII-деревья ссылок не создают — не использовать).

## Карта

- [idea.md](idea.md) — концепт: что строим и почему так.
- [roadmap.md](roadmap.md) — план по фазам; единственный источник правды «что сделано».
- [functional.md](functional.md) — версионно-независимый каталог возможностей продукта.
- [decisions.md](decisions.md) — журнал решений (включая ночные).
- [manuals/languageServers.md](manuals/languageServers.md) — как поставить vtsls/Phpactor для TS/PHP.
- [manuals/acpAgentsSpec.md](manuals/acpAgentsSpec.md) — спека реестра ACP-агентов `~/.jetbrains/acp.json` (самодостаточная, для LLM).
- [manuals/providersSpec.md](manuals/providersSpec.md) — спека `providers.json`: свои LLM-провайдеры для прямого чата (самодостаточная, для LLM).
- [manuals/pipelinesSpec.md](manuals/pipelinesSpec.md) — спека `.vibe/pipelines.json`: цепочки шагов-ролей (самодостаточная, для LLM).
- [manuals/serversSpec.md](manuals/serversSpec.md) — спека `.vibe/servers.json`: декларативный дев-стек с волнами и readyCheck (самодостаточная, для LLM).
- [knowledge/README.md](knowledge/README.md) — база знаний: индекс обязателен, запись без строки в индексе не существует.
- [../../FORK_CHANGES.md](../../FORK_CHANGES.md) — реестр отклонений от апстрима + плейбук синка.
- [../../CLAUDE.md](../../CLAUDE.md) — специфика проекта для сессий Claude.

## Конвенции

- Наши доки живут только в `docs/vibe/` (у апстрима свой `docs/` — не трогаем). Имена файлов и папок — camelCase (исключения: README.md и общепринятые верхнеуровневые).
- Мануалы — только в `docs/vibe/manuals/` (папка появится с первым мануалом).
- Фича с форматом/сценарием обязана иметь самодостаточную спеку + пример (проверка: агент в чистом проекте собирает файл, не выходя в интернет).
