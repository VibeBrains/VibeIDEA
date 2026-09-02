# База знаний VibeIDEA

**Правило:** запись без строки в этой таблице не существует — её никто не найдёт. Добавил файл → добавь строку. Дублирующих индексов не заводить: «дублирующие списки одного множества расходятся по построению» (урок VibeIDE).

Формат записи: role-тег (`[правило]`/`[инструмент]`/`[архитектура]`/`[баг]`/`[инцидент]`/`[квирк]`) + Контекст / Суть / Применение.

## ui

| Файл | О чём |
|---|---|
| [settingsScroll.md](ui/settingsScroll.md) | [баг] `Configurable.NoScroll` = «скролл делаю сам»: без маркера платформа добавляет второй скролл и горизонтальную полосу по ширине html-подсказок (Провайдеры/Модели, 24.08), с маркером но без своего `JBScrollPane(TracksViewportWidthPanel(...))` страницу обрезает по краю окна (Агент, 28.08 — нашёл владелец). Почему на это нет юнит-теста: тестовый модуль намеренно без платформенных зависимостей. |
| [swingChatLayout.md](ui/swingChatLayout.md) | [баг] BoxLayout-лента без maxHeight у строк растягивает сообщения на весь вьюпорт; пузырь в BorderLayout.CENTER — на всю ширину. ChatRow с maxHeight=preferred + якорь NORTH. Урок: UI не отдаётся без визуальной проверки. |
| [focuslessListPopup.md](ui/focuslessListPopup.md) | [архитектура] Меню `@` под JTextArea без потери фокуса: `PopupChooserBuilder.setRequestFocus(false)` (паттерн FileTextFieldImpl); клавиши — ТОЛЬКО локальные шорткаты `registerCustomShortcutSet`: KeyListener/InputMap на JTextComponent не видят стрелки/Backspace/Esc — их раньше забирает IdeKeyEventDispatcher через keymap (`EditorBackSpace` и т.п. включены на любом текстовом поле); `showUnderneathOf` не флипает вверх — место считать самим; ACP-блоки контекста и режимы. |

## design

| Файл | О чём |
|---|---|
| [detectorSilence.md](design/detectorSilence.md) | [правило] Каталог из 81 детектора: каждое правило обязано иметь ветку «не знаю» и в ней молчать (нечитаемые стили, неразложенный элемент, крутилка, явный `inputmode`, отсутствующая рамка) — обвиняющий по догадке детектор выключают целиком. Плюс: класс правила (пол/вкус) решает каталог, а не само правило; русские регэкспы — данные, и живут в `DesignPhrases`. |

## architecture

| Файл | О чём |
|---|---|
| [benchmarksMeasureHarness.md](architecture/benchmarksMeasureHarness.md) | [правило] Бенчмарк меряет связку «модель + харнесс»: тот же Opus 4.8 — 69,2 % своим стендом и 51,9 % на SEAL (17,3 пункта разницы), у Gemini 3.1 Pro разброс 26,4. Вендорских процентов в `ModelQuirks` и в выборе модели не приводить; сравнивать только своим харнессом через `/measure`. |
| [upstreamBoundary.md](architecture/upstreamBoundary.md) | [правило] Граница «наш код ↔ upstream»: платформу не правим ради стиля, свой код — в модулях `com.vibe.*`; каждая точечная правка платформы — запись в FORK_CHANGES.md. Перенос фундаментального правила VibeIDE с обострением: IC огромен, merge-налог выше. |

## build

| Файл | О чём |
|---|---|
| [bundledPluginIndex.md](build/bundledPluginIndex.md) | [инцидент] LSP4IJ не грузился НИ В ОДНОЙ собранной сборке: платформа грузит встроенные плагины только по `plugins/plugin-classpath.txt`, а каталог, скопированный туда после генерации индекса, невидим. Штатный механизм — `ProductProperties.getAdditionalPluginPaths()`. После каждой сборки читать в логе `Loaded bundled plugins`. |
| [threePackagingSystems.md](build/threePackagingSystems.md) | [грабли] Состав дистрибутива решают ТРИ места: `BUILD.bazel` (компиляция), `.iml` (модель проекта) и раскладка плагина в `VibeIdeaProperties.kt` (упаковка). Правка первых двух даёт зелёные тесты и мёртвую фичу в dmg — проверено на ZXing и PDFBox. Проверка — только сборкой инсталлятора. |
| [bazelBuild.md](build/bazelBuild.md) | [инструмент] Сборка IC на Bazel (2026): тулчейн герметичный (bazelisk + JBR сами качаются), android-репо обязателен для инсталлятора, ~100 GB диска на полный цикл, 16 GB RAM впритык (CI добавляет 30 GB swap). Команды запуска/сборки/тестов. |
| [windowsBuild.md](build/windowsBuild.md) | [грабли] Сборка инсталлятора под Windows (02.09.2026): клон без `core.longpaths` обрывается и оставляет пустой индекс (лечится `git reset` + `restore`, не переклонированием); `python3` — заглушка магазина Microsoft, гейты берут интерпретатор через `pythonBin.sh`; разделители путей в Python-блоках гейтов; `strings` нет; иконка лаунчера и картинки NSIS без явных путей — стоковые IntelliJ (генератор `makeWinImages.py` из того же `.icns`); клон `android/` — по дате синка, не по HEAD; **Phpactor на Windows не стартует** (phar требует `ext-posix`); сравнение exe/zip сборка в dev-режиме пропускает — проверять тихой установкой. |

## gitAndTools

| Файл | О чём |
|---|---|
| [shallowClonePush.md](gitAndTools/shallowClonePush.md) | [инцидент] Первый пуш форка отвергнут: `remote unpack failed: index-pack failed` — у поверхностного клона корень ссылается на несуществующих родителей. Лечится пересборкой корня через `commit-tree` (13 с на 183 коммита), а не докачкой истории. Проверять `.git/shallow` ДО публикации. |
| [shallowDeepenGithub.md](gitAndTools/shallowDeepenGithub.md) | [квирк] `git fetch --deepen` против GitHub падает «error processing shallow info: 4» — ретраи бесполезны; дозакачивать историю через `--shallow-since` со сдвигом даты. |
| [caseInsensitiveFs.md](gitAndTools/caseInsensitiveFs.md) | [квирк] Case-insensitive APFS: `README.md` ≡ `readme.md` — копирование в каталог апстрима молча перетирает его файл (наш README перетёр их docs/readme.md; восстановлен). Свои файлы — только в своём неймспейсе; после копирования смотреть `git status`. |

## languages

| Файл | О чём |
|---|---|
| [platformLspVsLsp4ij.md](languages/platformLspVsLsp4ij.md) | [архитектура] `platform/lsp` открыт, лежит в дереве и уже едет в наш дистрибутив (31 кастомайзер, API не экспериментальный). DAP в платформе нет, поэтому LSP4IJ всё равно пришлось бы оставить — миграция без выигрыша. Решение: ждать DAP, проверять при каждом синке. |
| [lspStack.md](languages/lspStack.md) | [архитектура] PHP/TS в открытом дистрибутиве: PhpStorm/WebStorm-плагины закрыты → LSP4IJ (EPL-2.0, +DAP) + Phpactor (MIT) + vtsls (MIT); Intelephense бандлить НЕЛЬЗЯ (EULA §5c); `platform/lsp` открыт (2026-06, Apache-2.0), но без DAP. Лицензии проверены по первоисточникам. |
| [i18nRatchet.md](languages/i18nRatchet.md) | [грабли] Храповик локализации нельзя считать `grep`-ом: диапазон `[А-Яа-яЁё]` сравнивается по байтам UTF-8 и записывает в кириллицу `—`, `·`, `…`, `«»`, стрелки — планка держалась на пунктуации, а непереведённая кнопка тонула в шуме. Счёт переписан на python по кодовым точкам, комментарии вырезаны, планка 0. Плюс правила исключений и почему строка каталога — `val ... get()`. |

## agents

| Файл | О чём |
|---|---|
| [acpClient.md](agents/acpClient.md) | [архитектура] Агентская обвязка = свой ACP-клиент: JetBrains AI Chat закрыт и в IC отсутствует; открыты спека ACP + Kotlin SDK (Apache-2.0). Референсы архитектур: Continue (core-процесс + JCEF), ProxyAI (чистый JVM), jetbrains-cc-gui. Точки расширения платформы для агентского плагина. |
| [acpGatesTerminal.md](agents/acpGatesTerminal.md) | [архитектура] Перенос терминала/хуков/VERIFY-GATE/turn-checks в ACP-модель: цикл агента в процессе Claude Code → всё цепляется за `session/update` + `request_permission`, а не за свой tool-loop. Реестр tool-call'ов как предпосылка; Claude-терминал через `_meta.terminal_output` (стандартные `terminal/*` мертвы для CC); `wait_for_exit` уводить с reader-потока; preToolUse блокирует только в точках клиента; bounce = повторный `session/prompt`; предохранители переживают рестарт. |
| [modelCatalogCache.md](agents/modelCatalogCache.md) | [архитектура] Кэш каталогов моделей: отдаём всегда без TTL, обновляем фоном и параллельно, пишем только успешный ответ (401/timeout не стирают вчерашний каталог), запись помнит отпечаток endpoint, место — системный каталог IDE, а не `.vibe`. Грабли: `invokeAndWait` с пула в модальных настройках = дедлок; снимок `staticModelIds` — до подмешивания кэша, иначе кэш зарастит curated-пикер; `connectTimeout` живёт на `HttpClient`, отсюда отдельный `LlmClient.forCatalog()`. |
| [contextPoisoning.md](agents/contextPoisoning.md) | [архитектура] Защита входящего контекста: невидимые символы (включая теговый блок U+E0000–E007F) и bidi вырезаем молча, фразы-инструкции только сообщаем (правка чужого файла хуже предупреждения), секреты — сообщаем, маскируем по настройке, файл не трогаем. Единая точка `ContextSerializer.load` + `IdeFileOps`. Грабли: в Java `(?i)` складывает регистр только ASCII, а `\b` не знает кириллицу — нужен `(?iU)`, иначе гейт молча не находит ничего. |
| [watchPipeline.md](agents/watchPipeline.md) | [квирк] `/watch`: кадры по сменам сцен (прореживание по времени, а не по индексу), якорь первого кадра против статичного ролика, `-o video.%(ext)s` против выбора контейнера yt-dlp, `s[:=]` в showinfo (**на ffmpeg 8.1.2 проверено: `s:`**, вопреки записи VibeIDE), видео-свидетельство сильнее подсказки по имени, `(attached pic)` — обложка, ненулевой код `ffmpeg -i` — норма. Почему бинари не бандлятся. |
| [providersCatalog.md](agents/providersCatalog.md) | [архитектура] Авточитаемый каталог `.vibe/providers/*.jsonc` вместо вшитых провайдеров (решение №24): каталог — СЛАБЕЙШИЙ слой (глобальный каталог → проектный каталог → глобальный providers.json → проектный providers.json), иначе засеянный active:false глушил живую глобаль пользователя; «запись без active активна» — осознанно не тристейт; один строгий resolveExtends по слитому реестру; ключ без рестарта через топик ProvidersChangeListener; пустое поле не стирает общий apiKeyRef. Грабли: вложенные блочные комментарии Kotlin (`/*` в KDoc от глоб-паттерна = Unclosed comment), trailing lambda против новых дефолт-параметров, где в исходниках VibeIDE лежат конфиги «встроенных» (sendLLMMessage.impl.ts, vibeideSettingsTypes.ts). |
