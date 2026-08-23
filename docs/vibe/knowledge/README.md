# База знаний VibeIDEA

**Правило:** запись без строки в этой таблице не существует — её никто не найдёт. Добавил файл → добавь строку. Дублирующих индексов не заводить: «дублирующие списки одного множества расходятся по построению» (урок VibeIDE).

Формат записи: role-тег (`[правило]`/`[инструмент]`/`[архитектура]`/`[баг]`/`[инцидент]`/`[квирк]`) + Контекст / Суть / Применение.

## architecture

| Файл | О чём |
|---|---|
| [upstreamBoundary.md](architecture/upstreamBoundary.md) | [правило] Граница «наш код ↔ upstream»: платформу не правим ради стиля, свой код — в модулях `com.vibe.*`; каждая точечная правка платформы — запись в FORK_CHANGES.md. Перенос фундаментального правила VibeIDE с обострением: IC огромен, merge-налог выше. |

## build

| Файл | О чём |
|---|---|
| [bazelBuild.md](build/bazelBuild.md) | [инструмент] Сборка IC на Bazel (2026): тулчейн герметичный (bazelisk + JBR сами качаются), android-репо обязателен для инсталлятора, ~100 GB диска на полный цикл, 16 GB RAM впритык (CI добавляет 30 GB swap). Команды запуска/сборки/тестов. |

## gitAndTools

| Файл | О чём |
|---|---|
| [shallowDeepenGithub.md](gitAndTools/shallowDeepenGithub.md) | [квирк] `git fetch --deepen` против GitHub падает «error processing shallow info: 4» — ретраи бесполезны; дозакачивать историю через `--shallow-since` со сдвигом даты. |
| [caseInsensitiveFs.md](gitAndTools/caseInsensitiveFs.md) | [квирк] Case-insensitive APFS: `README.md` ≡ `readme.md` — копирование в каталог апстрима молча перетирает его файл (наш README перетёр их docs/readme.md; восстановлен). Свои файлы — только в своём неймспейсе; после копирования смотреть `git status`. |

## languages

| Файл | О чём |
|---|---|
| [lspStack.md](languages/lspStack.md) | [архитектура] PHP/TS в открытом дистрибутиве: PhpStorm/WebStorm-плагины закрыты → LSP4IJ (EPL-2.0, +DAP) + Phpactor (MIT) + vtsls (MIT); Intelephense бандлить НЕЛЬЗЯ (EULA §5c); `platform/lsp` открыт (2026-06, Apache-2.0), но без DAP. Лицензии проверены по первоисточникам. |

## agents

| Файл | О чём |
|---|---|
| [acpClient.md](agents/acpClient.md) | [архитектура] Агентская обвязка = свой ACP-клиент: JetBrains AI Chat закрыт и в IC отсутствует; открыты спека ACP + Kotlin SDK (Apache-2.0). Референсы архитектур: Continue (core-процесс + JCEF), ProxyAI (чистый JVM), jetbrains-cc-gui. Точки расширения платформы для агентского плагина. |
