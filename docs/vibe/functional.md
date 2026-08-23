# Каталог возможностей VibeIDEA

Версионно-независимый каталог «что умеет продукт». Одна исчерпывающая запись на возможность; расширили — переписываем пункт, не дописываем второй. Чистые баг-фиксы сюда не идут.

## 🧩 Платформа

*(наследуемое от intellij-community описывается только там, где мы меняем поведение)*

- **Собственный дистрибутив VibeIDEA** — продукт собирается из исходников в неподписанный dmg/sit (macOS arm64): свой product code `VI`, `VibeIDEA.app`, свои иконки; вендорные mcpserver и featuresTrainer из комплекта исключены. Сборка: `./vibeidea-installers.cmd -Dintellij.build.target.os=current`.

## 🌐 Языки

- **TypeScript/JavaScript через LSP (vtsls)** — completion, диагностика, навигация, hover, форматирование для `*.ts/tsx/js/jsx/mts/cts/mjs/cjs`; сервер vtsls (MIT) подключён через бандленный LSP4IJ, подсветка — TextMate. Сервер ставится пользователем один раз ([мануал](manuals/languageServers.md)); IDE ищет его в PATH и типовых местах установки — GUI-приложениям macOS shell-PATH не достаётся, поэтому известные каталоги проверяются явно.
- **PHP через LSP (Phpactor)** — то же для `*.php`; Phpactor (MIT). Intelephense не поставляется (его EULA запрещает редистрибуцию) — задокументирован путь самостоятельной установки как «премиум»-вариант.

## 🤖 Агентская обвязка

- **Свои LLM-провайдеры (`providers.json`)** — прямой стриминговый чат с любым openai/anthropic-совместимым endpoint (Ollama, DeepSeek, Z.AI, свой прокси…) без ACP-агента: записи `LLM: провайдер · модель` в той же панели. Контракт VibeIDE: глобальный `~/.vibe/providers.json` + проектный (перекрытие по полям, мерж моделей по id), JSONC, битая запись не роняет реестр, `extends`-клоны, `order`. Ключи — только `apiKeyRef` (хранилище ОС) / `apiKeyEnv` + `.vibe/.env` (файл можно коммитить); localhost-провайдер помечается «локальная модель»; реестр сканируется Config Guard (не-HTTPS, сырой IP, секрет в headers/query — предупреждения в чате). Спека: [manuals/providersSpec.md](manuals/providersSpec.md).
- **ACP-клиент (Vibe Agent)** — тулвиндоу с чатом любого локального ACP-агента (Claude Code через `claude-agent-acp`, Codex, Gemini CLI и др.): стриминг ответа, permission-запросы диалогом (закрытый диалог = отказ — молчаливого «разрешить» не существует), чтение файлов агентом видит несохранённые правки редактора (Document-first), записи идут через WriteCommandAction для открытых файлов и с асинхронным VFS-refresh для внешних. Реестр агентов совместим с `~/.jetbrains/acp.json` (битая запись пропускается с предупреждением и не роняет реестр); агент запускается без оболочки (command+args). Дефолтный агент — Claude Code через `npx @agentclientprotocol/claude-agent-acp`.
