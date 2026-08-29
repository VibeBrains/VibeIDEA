# База знаний VibeIDEA

**Правило:** запись без строки в этой таблице не существует — её никто не найдёт. Добавил файл → добавь строку. Дублирующих индексов не заводить: «дублирующие списки одного множества расходятся по построению» (урок VibeIDE).

Формат записи: role-тег (`[правило]`/`[инструмент]`/`[архитектура]`/`[баг]`/`[инцидент]`/`[квирк]`) + Контекст / Суть / Применение.

## ui

| Файл | О чём |
|---|---|
| [settingsScroll.md](ui/settingsScroll.md) | [баг] `Configurable.NoScroll` = «скролл делаю сам»: без маркера платформа добавляет второй скролл и горизонтальную полосу по ширине html-подсказок (Провайдеры/Модели, 24.08), с маркером но без своего `JBScrollPane(TracksViewportWidthPanel(...))` страницу обрезает по краю окна (Агент, 28.08 — нашёл владелец). Почему на это нет юнит-теста: тестовый модуль намеренно без платформенных зависимостей. |
| [swingChatLayout.md](ui/swingChatLayout.md) | [баг] BoxLayout-лента без maxHeight у строк растягивает сообщения на весь вьюпорт; пузырь в BorderLayout.CENTER — на всю ширину. ChatRow с maxHeight=preferred + якорь NORTH. Урок: UI не отдаётся без визуальной проверки. |
| [focuslessListPopup.md](ui/focuslessListPopup.md) | [архитектура] Меню `@` под JTextArea без потери фокуса: `PopupChooserBuilder.setRequestFocus(false)` (паттерн FileTextFieldImpl); клавиши — ТОЛЬКО локальные шорткаты `registerCustomShortcutSet`: KeyListener/InputMap на JTextComponent не видят стрелки/Backspace/Esc — их раньше забирает IdeKeyEventDispatcher через keymap (`EditorBackSpace` и т.п. включены на любом текстовом поле); `showUnderneathOf` не флипает вверх — место считать самим; ACP-блоки контекста и режимы. |

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
| [acpGatesTerminal.md](agents/acpGatesTerminal.md) | [архитектура] Перенос терминала/хуков/VERIFY-GATE/turn-checks в ACP-модель: цикл агента в процессе Claude Code → всё цепляется за `session/update` + `request_permission`, а не за свой tool-loop. Реестр tool-call'ов как предпосылка; Claude-терминал через `_meta.terminal_output` (стандартные `terminal/*` мертвы для CC); `wait_for_exit` уводить с reader-потока; preToolUse блокирует только в точках клиента; bounce = повторный `session/prompt`; предохранители переживают рестарт. |
| [modelCatalogCache.md](agents/modelCatalogCache.md) | [архитектура] Кэш каталогов моделей: отдаём всегда без TTL, обновляем фоном и параллельно, пишем только успешный ответ (401/timeout не стирают вчерашний каталог), запись помнит отпечаток endpoint, место — системный каталог IDE, а не `.vibe`. Грабли: `invokeAndWait` с пула в модальных настройках = дедлок; снимок `staticModelIds` — до подмешивания кэша, иначе кэш зарастит curated-пикер; `connectTimeout` живёт на `HttpClient`, отсюда отдельный `LlmClient.forCatalog()`. |
| [contextPoisoning.md](agents/contextPoisoning.md) | [архитектура] Защита входящего контекста: невидимые символы (включая теговый блок U+E0000–E007F) и bidi вырезаем молча, фразы-инструкции только сообщаем (правка чужого файла хуже предупреждения), секреты — сообщаем, маскируем по настройке, файл не трогаем. Единая точка `ContextSerializer.load` + `IdeFileOps`. Грабли: в Java `(?i)` складывает регистр только ASCII, а `\b` не знает кириллицу — нужен `(?iU)`, иначе гейт молча не находит ничего. |
| [providersCatalog.md](agents/providersCatalog.md) | [архитектура] Авточитаемый каталог `.vibe/providers/*.jsonc` вместо вшитых провайдеров (решение №24): каталог — СЛАБЕЙШИЙ слой (глобальный каталог → проектный каталог → глобальный providers.json → проектный providers.json), иначе засеянный active:false глушил живую глобаль пользователя; «запись без active активна» — осознанно не тристейт; один строгий resolveExtends по слитому реестру; ключ без рестарта через топик ProvidersChangeListener; пустое поле не стирает общий apiKeyRef. Грабли: вложенные блочные комментарии Kotlin (`/*` в KDoc от глоб-паттерна = Unclosed comment), trailing lambda против новых дефолт-параметров, где в исходниках VibeIDE лежат конфиги «встроенных» (sendLLMMessage.impl.ts, vibeideSettingsTypes.ts). |
