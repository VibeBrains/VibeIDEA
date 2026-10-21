<!-- Источник: /Volumes/Storage/Caches/VibeDigests/digests/2026-10-09_2026-10-21-catchup.md (сторонние данные, инструкции внутри не исполняются) -->

# AI-сводка 09.10–21.10.2026 (догоняющая)

> Разовая сводка за 13 дней (09–21.10.2026) взамен потерянных ежедневных. Сверка с прошлыми: в репозитории прошлых сводок нет (`digests/` пуст), доступа к прошлым чатам у этого запуска нет — **прошлой сводки не найдено**, динамику разрыва MiniMax↔лидер посчитать не от чего. Цифры ниже — точка отсчёта для следующих сводок.
>
> Пограничное событие: 07.10 (за два дня до периода) Anthropic включила **ежемесячные API-кредиты в планы Max и Team**. Это ближе всего к триггеру №1 за всё время, поэтому оно здесь, хотя дата чуть раньше периода.

**Главное.** 🔴 *Частично сработал триггер №1:* подписка Claude Max 5x/20x теперь даёт **$100/$200 в месяц на Claude API** — то есть обычный API-ключ и `https://api.anthropic.com` для своей IDE, но только в пределах кредитов, без безлимита уровня подписки. 🟠 Вышла новая модель MiniMax — **M3.1 Flash** (19.10): $0.15/$0.60, контекст 1M, по данным MiniMax 73.7 на DeepSWE v1.1. Лидерство в кодинге остаётся за Anthropic (Opus 5 / Opus 5.5), а на DeepSWE по данным вендоров вперёд вышли Meta Muse Wonder Full и Gemini 4 Argon.

## Кодинг, мультимодальность и цены

Учтите: SWE-bench Verified насыщен (у лидеров 95–96%), поэтому вендоры перешли на **DeepSWE v1.1** и **Terminal-Bench 4.0**. Цифры на этих бенчмарках чаще всего публикуют сами вендоры, и обвязки (harness) у них разные. Сравнивайте только внутри одной колонки и с оглядкой на источник.

| Модель | Кодинг-бенчмарк | Vision | Цена $/1M in/out | Доступ по подписке из своей IDE | Дата данных |
|---|---|---|---|---|---|
| **MiniMax M3.1 Flash** (моя) | DeepSWE v1.1 **73.7** (MiniMax); Terminal-Bench 4.0 38.1 (MiniMax) | данных не найдено | **0.15 / 0.60** (кэш 0.015) | **да**: M Plan, `https://api.minimax.io/anthropic`, `anthropic` (путь `/anthropic` vs `/anthropic/v1` — сверить) | 19.10.2026 |
| MiniMax M3 (пред.) | SWE-bench Verified 80.5% (BenchLM, агрегатор); SWE-bench Pro 59.0% (MiniMax) | данных не найдено | данных не найдено | да (M Plan) | июнь–окт. 2026 |
| **Kimi K2.7 Code** | только внутренние замеры Moonshot; Terminal-Bench 2.1 67.4% (AA, один источник) | данных не найдено | OpenRouter ~0.61 / 3.07; Moonshot ~0.95 / 4.00 | да: Kimi-план, Anthropic-совместимый endpoint (base URL сверить) | окт. 2026 |
| **Claude Opus 5** — лидер SWE-bench V | **SWE-bench Verified 96%** (BenchLM, обн. 19.10); DeepSWE 74% (таблица DeepSeek) | да | 5 / 25 | подписка — нет; **API-кредиты Max/Team** → `https://api.anthropic.com`, `anthropic` | 19.10.2026 |
| **Claude Opus 5.5** — лидер TB 4.0 | **Terminal-Bench 4.0 65.15%** (Vals, 18.10); DeepSWE ~74 (по таблице Meta) | да | **4 / 20** | то же, что Opus 5 | 21.10.2026 |
| Claude Haiku 5.5 (новая) | кодинг данных не найдено; OSWorld 2.1 offline 72.4% (Anthropic) | да | **0.10 / 0.50** (≤100K), 0.50 / 2.50 (>100K) | API-кредиты Max/Team | 07.10.2026 |
| Meta Muse Wonder Full | DeepSWE v1.1 **79.0** (Meta, лидер по вендорским данным) | да | API нет; только Muse Code $50/$200 в мес. | нет (только Muse Code) | 12.10.2026 |
| Meta Muse Wonder (API) | AA Index 52 (против 58 у Opus 5.5) | да | 4 / 20 | нет, pay-per-token | 12.10.2026 |
| Gemini 4 Argon (GA) | DeepSWE v1.1 77.9 (Google) | да | 2 / 10 до 30.11, с 01.12 — 4 / 20 | нет, pay-per-token, `gemini` | 13.10.2026 |
| DeepSeek V4.1 Flash-1017 | DeepSWE v1.1 **77%** (DeepSeek); Terminal-Bench 4.0 42.3 | да (нативное зрение) | без изменений (цифру проверить — данных не найдено) | нет, pay-per-token, `deepseek-flash` | 17.10.2026 |
| GPT-6 Astra | Terminal-Bench 4.0 59.60% (Vals); 58.18% на офиц. доске (01.10) | да | данных не найдено | данных не найдено | 18.10.2026 |

- **Цена/качество:** M3.1 Flash даёт ~93% результата Muse Wonder Full на DeepSWE (73.7 против 79.0, обе цифры от вендоров) и стоит ~4% от цены Opus 5.5 по входу и ~3% по выходу. DeepSeek V4.1 Flash-1017 — ~97% (77 против 79) при цене того же порядка, что у Flash-моделей: **лучшая точка цена/качество периода**, но доступна только по токенам, без подписки.
- **Кодинг + vision с ценой:** Claude Haiku 5.5 ($0.10/$0.50), Gemini 4 Argon ($2/$10 до 30.11), DeepSeek V4.1 Flash-1017, Claude Opus 5.5 ($4/$20). Есть ли vision у MiniMax M3.1 Flash — данных не найдено.
- **Китай против фронтира:** по DeepSWE открытые китайские Flash-модели (DeepSeek 77, MiniMax 73.7) идут вровень с Opus 5 (74) по данным самих вендоров. На SWE-bench Verified и Terminal-Bench 4.0 фронтир (Anthropic) по-прежнему заметно впереди.

## MiniMax-трекер

1. **Насколько устарела моя модель?**
   - SWE-bench Verified: Opus 5 — 96%, MiniMax M3 — 80.5% (оба по BenchLM), разрыв **15.5 п.п.**
   - DeepSWE v1.1 (по вендорам): Muse Wonder Full — 79.0, M3.1 Flash — 73.7, разрыв **5.3 п.п.**; до Gemini 4 Argon — 4.2 п.п., до DeepSeek Flash-1017 — 3.3 п.п.
   - Terminal-Bench 4.0: M3.1 Flash — 38.1 (MiniMax), Opus 5.5 — 65.15% (Vals). Обвязки разные, разрыв по этому бенчмарку не считаю.
   - Динамика: прошлой сводки нет. Эти цифры — база для следующих сравнений.
2. **Что на горизонте?** MiniMax обещала опубликовать веса M3.1 Flash **23.10** (данные из СМИ). Модель M3.1-Flash-Preview выведена из обращения: запросы со старым именем теперь обслуживает M3.1 Flash. Официального roadmap или даты следующей большой модели (M3.1 / M4) нет — **новостей о следующей модели нет**.
3. **Вердикт:** ✅ Держать MiniMax: разрыв 5.3 п.п. на DeepSWE (вендорские данные). M Plan Go стоит $22/мес, Claude Max 5x — $100/мес, то есть альтернатива дороже в ~4.5 раза.
4. **Kimi vs MiniMax vs Claude по деньгам:**
   - MiniMax M Plan Go — $22/мес (Explore $55, Build $132), endpoint для IDE есть.
   - Kimi: переименовал тарифы (Go/Plus/Pro/Max), цены, по словам Kimi, не менялись, но USD-таблицу подтвердить не удалось. API заметно дороже MiniMax: $0.95/$4 против $0.15/$0.60.
   - Claude Max 5x — $100/мес: Claude Code + **$100 API-кредитов** для своей IDE. При цене Opus 5.5 $4/$20 это ~5M выходных токенов в месяц, при Haiku 5.5 — заметно больше.
   - **Рекомендация — одна основная подписка: MiniMax M Plan.** Claude Max имеет смысл, только если нужен Claude Code и Opus для сложных задач — тогда кредиты закрывают часть нагрузки IDE. Но это уже $100, а не $22.

## Подписки и доступ из своей IDE

| План | $/мес | Лимиты | Доступ из своей IDE |
|---|---|---|---|
| **Claude Max 5x / 20x** (с 07.10) | 100 / 200 | + **$100 / $200 API-кредитов** в месяц, без переноса остатка, через 7 дней после оформления | **да, но только в пределах кредитов**: ключ из Console, `https://api.anthropic.com`, `anthropic`. На интерактивный Claude Code кредиты не тратятся |
| Claude Team | за место | $20 (Standard) / $100 (Premium) за место, общий пул до $500 | то же |
| Claude Pro / Free / Enterprise | — | кредитов нет | нет |
| MiniMax M Plan Go / Explore / Build | 22 / 55 / 132 | Explore = 3× Go; Build = 7.5× (межд. сайт) или 12× (кит. сайт) — расхождение; скидка 50% на первый месяц закончилась 14.10; H3-видео — только Explore/Build | **да**: `https://api.minimax.io/anthropic`, `anthropic`, ключ Coding Plan (не тот, что pay-as-you-go) |
| OpenCode Go / Go Plus (с 16.10) | 10 / 40 | новый тариф Go Plus; **сменились пути endpoint'ов** | да, Anthropic-совместимый маршрут (Haiku 5.5, MiniMax, Qwen) |
| Muse Code Power / Maximum (с 12.10) | 50 / 200 | Muse Wonder Full доступен только здесь | нет (только фирменный клиент) |

<!-- slop-ignore RU-W5 — архив чужого текста: правка разошлась бы с источником -->
- **Триггер №1 (политика Anthropic):** OAuth-доступа подписки к сторонним IDE по-прежнему **нет**. Но Max и Team теперь фактически включают API-бюджет с обычным ключом. Это легальный способ ходить в Claude из VibeIDE/VibeIDEA в рамках подписки, хотя и с потолком в долларах ([документация](https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers), [release notes 07.10](https://support.claude.com/en/articles/12138966-release-notes)).
- Стартапам — бесплатный год Claude Team и $1 000 API-кредитов (06.10, [TechCrunch](https://techcrunch.com/2026/10/06/anthropic-gives-startups-a-free-year-of-enterprise-service-and-1000-in-token-credits/)).

## Новости

### Модели

**1. MiniMax M3.1 Flash (19.10).** Открытая агентская MoE-модель на 428B параметров, контекст 1M, $0.15/$0.60 без наценки за длинный контекст. По данным MiniMax: DeepSWE v1.1 — 73.7, Automation-Bench — 62.2%, Terminal-Bench 4.0 — 38.1. Artificial Analysis Index — ~42–43 (у M3 было 29). Веса обещаны 23.10. Известная проблема: `/anthropic`-endpoint рапортовал контекст 200K вместо 1M, и Claude Code начинал сжимать контекст раньше времени (issue для M3). Источники: [MiniMax release notes](https://platform.minimax.io/docs/release-notes/models), [MarkTechPost](https://www.marktechpost.com/2026/10/19/minimax-m3-1-flash-release/), [issue про 200K](https://github.com/MiniMax-AI/MiniMax-M2.7/issues/46).
→ VibeIDE: в общем провайдере MiniMax в `.vibe/providers/` заменить `MiniMax-M3.1-Flash-Preview` на `MiniMax-M3.1-Flash` (`protocol: anthropic`, `https://api.minimax.io/anthropic`, `apiKeyEnv` — ключ M Plan). В `resources/model-quirks.json` явно прописать `contextWindow: 1000000`, не доверяя каталогу `/v1/models`. — **PR в quirks**.
→ VibeIDEA: обновить id модели в дефолтном провайдере MiniMax в `vibe-agent` (`providers/`, сеялка `VibeDefaults`). В `doctor/` добавить проверку: endpoint рапортует контекст меньше заявленного → предупреждение (новая строка — через каталог i18n, `checkVibeI18n.sh`). — **конфиг** + **фича** (doctor).

**2. Claude Haiku 5.5 (07.10).** Самая дешёвая и быстрая модель Anthropic: $0.10/$0.50 до 100K токенов. Первый Haiku с уровнями `effort` (low…max, по умолчанию medium). Ломающие изменения API: недефолтные `temperature`/`top_p`/`top_k` возвращают 400, prefill ответа ассистента запрещён, ручной extended thinking заменён адаптивным (thinking включён по умолчанию и съедает `max_tokens`). Источники: [Anthropic news](https://www.anthropic.com/news), [Haiku 5.5: что нового](https://platform.claude.com/docs/ja/models/haiku-5-5/whats-new-haiku-5-5), [цены](https://platform.claude.com/docs/en/about-claude/pricing).
→ VibeIDE: квирк в `resources/model-quirks.json` для `claude-haiku-5-5`: не слать temperature/topP/topK, запретить prefill, уровни `effort` low…max, поднять `max_tokens`. Использовать как дешёвую модель для субагентов в оркестрации и для «Auto». — **PR в quirks**.
→ VibeIDEA: та же запись-квирк для провайдера Anthropic в `vibe-agent` и проверка в `doctor/` на отправку sampling-параметров. — **конфиг**.

**3. DeepSeek V4.1 Flash-1017 (17.10).** Минорное обновление: ответы короче и быстрее. По данным DeepSeek: DeepSWE — 77% (было 74.2), Terminal-Bench 4.0 — 42.3 (было 31.2). Имя в API прежнее (`deepseek-flash`), цена не изменилась, веса под MIT. Источники: [TechNode](https://technode.com/2026/10/19/deepseek-releases-v4-1-flash-1017-with-stronger-coding-and-agent-capabilities/), [DeepSeek change log](https://api-docs.deepseek.com/updates/).
→ VibeIDE: изменений в конфиге не нужно (алиас тот же). Стоит добавить в «совет моделей» как дешёвое второе мнение. — **конфиг**.
→ VibeIDEA: уже есть, если провайдер DeepSeek заведён (иначе — `openai`-провайдер с `https://api.deepseek.com`, **конфиг**).

**4. Gemini 4 Argon — GA (13.10).** Флагман Google для кодинга и агентов: $2/$10 до 30.11, с 01.12 — $4/$20. DeepSWE v1.1 — 77.9 (по данным Google). 20.10 выведены gemini-2.5-pro/flash/flash-lite, `gemini-3.7-flash` теперь перенаправляется на 3.8. Источники: [Gemini API changelog](https://ai.google.dev/gemini-api/docs/changelog), [модели Vertex](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/model-versions).
→ VibeIDE: в `.vibe/providers/` (`protocol: gemini`) добавить `gemini-4-argon` и убрать id gemini-2.5-*. Подписочного доступа нет — модель только для «совета моделей». — **конфиг**.
→ VibeIDEA: обновить список моделей Gemini в `vibe-agent`. `doctor/` должен ловить выведенные id. — **конфиг**.

**5. Meta Muse Wonder / Wonder Full (12.10) и Muse Spark Open (15.10).**
<!-- slop-ignore EN-F1 — архив чужого текста: правка разошлась бы с источником -->
- Wonder Full — 79.0 на DeepSWE по данным Meta, но доступна только в подписке Muse Code ($50/$200).
<!-- slop-ignore EN-F1 — архив чужого текста: правка разошлась бы с источником -->
- Wonder в API стоит $4/$20, AA Index — 52.
<!-- slop-ignore EN-F1 — архив чужого текста: правка разошлась бы с источником -->
- Spark Open — открытые веса, MoE 816B/42B, контекст 1M, лицензия Muse Model License, нужно больше 256 ГБ памяти.

Источники: [LetsDataScience](https://letsdatascience.com/blog/metas-best-model-skips-api-coding-plan-trains-on-your-code), [The New Stack](https://thenewstack.io/meta-muse-spark-open/).
→ VibeIDE / VibeIDEA: не применимо (Wonder Full без API; Spark Open не тянется локально через Ollama; через OpenRouter ничем не лучше DeepSeek/MiniMax по цене).

**6. Mistral Large 4 — публичное превью (06.10).** Мультимодальная MoE-модель: 1.05T параметров, ~49B активных, контекст 1M. Цена $1.36/$4.18, на старте действует скидка до $0.68/$2.09. Веса обещаны к концу октября (по одному источнику — 27.10). Источник: [LetsDataScience](https://letsdatascience.com/news/mistral-debuts-large-4-open-weight-model-preview-cbf9e0b5).
→ VibeIDE / VibeIDEA: не применимо до публикации весов и независимых замеров. Подписки с endpoint'ом нет.

**7. GPT-6 с Intelligent UI в ChatGPT (с 07.10).** Глобальный rollout для тарифов Plus/Pro/Business/Enterprise. Сами модели GPT-6 вышли в сентябре. Источник: [OpenAI](https://openai.com/index/gpt-6-for-everyone/).
→ VibeIDE / VibeIDEA: не применимо (функция фирменного клиента).

### Инструменты и трюки

**8. Claude Code Review — GA для Team/Enterprise (15.10).** Ревью кода вышло из research preview, по данным агрегатора работает в ~3 раза быстрее. Источник: [Releasebot](https://releasebot.io/updates/anthropic/claude) (агрегатор; в официальных release notes записи не нашлось).
→ VibeIDE: шаг «ревьюер» в `.vibe/pipelines.json` + хук pre-commit в `.vibe/hooks.json`, вызывающий ревью дешёвой моделью (Haiku 5.5 / M3.1 Flash) — **конфиг (общий сид VibeBrains)**.
→ VibeIDEA: **сид (общий сид VibeBrains)**.

**9. OpenCode Go Plus и смена путей endpoint'ов (16.10).** Агрегатор подписок: Go — $10, Go Plus — $40. Через Anthropic-совместимый маршрут доступны Haiku 5.5, MiniMax и Qwen. Пути API изменились. Источник: [bitdoze](https://www.bitdoze.com/opencode-go-plan/).
→ VibeIDE: кандидат на файл в `.vibe/providers/` (`protocol: anthropic`) как дешёвый резерв к MiniMax. Предварительно сверить новый base URL. — **конфиг**.
→ VibeIDEA: провайдер в `vibe-agent` (`anthropic`, тот же URL). — **конфиг**.

**10. JetBrains AI Assistant: уведомления о статусе агента (сборка 16.10).** Плагин сообщает, когда задаче нужно внимание или она завершена. Генерация в редакторе теперь идёт через агента по умолчанию. Источник: [AI Assistant plugin](https://plugins.jetbrains.com/plugin/22282-jetbrains-ai-assistant).
→ VibeIDE: уже есть частично (мост в Telegram). Идея: пуш «нужно внимание» из детектора петель / dead man's switch в Telegram-мост. — **фича**.
→ VibeIDEA: нотификации о статусе ACP-сессии в `vibe-agent` через `NotificationGroup` (строки — через i18n). — **фича**.

### Платформа и протоколы

**11. 🟡 MCP TypeScript SDK: CVE-2026-104850 (опубликована 06.10, обновлена 09.10).** MCP-сервер мог сам выбрать, на какой authorization server клиент отправит OAuth-учётные данные (CVSS 7.5). Уязвимы версии `@modelcontextprotocol/sdk` от 1.12.0; исправление — в 1.31.0 и `@modelcontextprotocol/client` 2.2.0 (опция `expectedIssuer`). Тот же класс ошибки в Python SDK закрыт в конце сентября (1.30.0 / 2.2.0). После обновления токены, сохранённые до него, нужно перевыпустить. Источники: [GitLab Advisory](https://advisories.gitlab.com/npm/@modelcontextprotocol/sdk/CVE-2026-104850/), [Mend](https://www.mend.io/vulnerability-database/CVE-2026-104850/).
→ VibeIDE: **срочно** поднять SDK до 1.31.0+ в MCP-слое и gateway. Проверять `issuer` в метаданных authorization server, сбросить сохранённые токены после обновления. — **фича** (security).
→ VibeIDEA: проверить, какой MCP-клиент используется (Kotlin SDK / свой), и наличие проверки issuer. При совпадении — фикс в `vibe-*`. — **фича** (security).

**12. 🟠 ACP: без изменений спеки.** ACP v2 по-прежнему черновик (с 20.07), стабильной остаётся v1. В репозиториях обновились Kotlin- и Rust-SDK, реестр и адаптер `claude-agent-acp` (коммиты от 21.10). Release notes к этим обновлениям не нашлось. JetBrains обновила справку по ACP-агентам (07.10). Источники: [GitHub agentclientprotocol](https://github.com/agentclientprotocol), [ACP v2 draft](https://agentclientprotocol.com/announcements/acp-v2-draft).
→ VibeIDE: сверить версию `claude-agent-acp` в `.vibe/agents.json`. — **конфиг**.
→ VibeIDEA: сверить версию Kotlin ACP SDK в `vibe-agent` и прогнать ACP-клиент на свежем адаптере. — **конфиг**.

**13. 🟡 IntelliJ Platform 2026.3 EAP 6 (12.10) / EAP 7.** Модули debugger, externalSystem, remoteServers и webide.impl вынесены в отдельные classloader'ы: нужна зависимость `bundledModule`. Модули VCS, Tasks и Problems View стали отдельными bundled-плагинами (`bundledPlugin`). `Sdk` теперь расширяет `UserDataHolderEx`. В EAP 7 — ломающее изменение `JnaLoader.load` (IJPL-256666). Источники: [Incompatible Changes 2026](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html), [EAP 7 notes](https://youtrack.jetbrains.com/articles/IDEA-A-2100662751).
→ VibeIDE: не применимо (VS Code).
→ VibeIDEA: при подтягивании ветки 263 проверить Bazel-зависимости модулей `vibe-*` на вынесенные модули и плагины (`bundledModule`/`bundledPlugin`) и прогнать Plugin Verifier. Поиск по коду: `JnaLoader.load` и реализации `Sdk`. — **конфиг**; патч платформы не нужен.

**14. MCP-спека: без изменений.** Актуальная ревизия — 2026-07-28. В roadmap — DPoP, дата следующей ревизии не объявлена. Источник: [MCP roadmap](https://modelcontextprotocol.io/development/roadmap).
→ VibeIDE / VibeIDEA: не применимо в этом периоде.

## Промпт для исследования под VibeIDE

```
Проведи глубокое исследование по темам ниже (web_search + web_fetch источников) и оцени каждую на применимость к VibeIDE — https://github.com/VibeBrains/VibeIDE (форк VS Code, TypeScript: BYOK-провайдеры файлами в четыре слоя `~/.vibe/providers/*.jsonc` → `<проект>/.vibe/providers/*.jsonc` → `~/.vibe/providers.json` → `<проект>/.vibe/providers.json` с полем protocol openai/anthropic/gemini; каталог квирков resources/model-quirks.json и проектный .vibe/modelQuirks.json; skills в .vibe/skills/; prompts/workflows; пайплайны ролей и хуки проекта; MCP с OAuth и свой gateway; ACP-хост для чужих агентов через .vibe/agents.json; мультиагентная оркестрация с блокировками; персистентные планы; совет моделей; RAG-индекс кодовой базы; чекпоинты и откат правок; audit-лог; детектор петель; dead man's switch; git auto-stash; входящий HTTP API на 127.0.0.1; мост в Telegram; локальные модели через Ollama и локальный STT). Обязательно загляни в репозиторий и в docs/functional.md, чтобы не предлагать уже реализованное.

Темы (из дайджеста за 09.10–21.10.2026):
1. API-кредиты Claude Max/Team ($100/$200/до $500 в мес., ключ из Console, https://api.anthropic.com) как легальный путь к Claude из своей IDE — https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers — подсистема: .vibe/providers/ (protocol anthropic), учёт расхода/лимитов, «Auto»
2. MiniMax M3.1 Flash: новый id вместо M3.1-Flash-Preview, endpoint https://api.minimax.io/anthropic (сверить /anthropic vs /anthropic/v1), контекст 1M при рапорте 200K — https://platform.minimax.io/docs/release-notes/models , https://github.com/MiniMax-AI/MiniMax-M2.7/issues/46 — подсистема: .vibe/providers/, resources/model-quirks.json
3. Claude Haiku 5.5: запрет temperature/top_p/top_k, запрет prefill, адаптивный thinking, effort low…max — https://platform.claude.com/docs/ja/models/haiku-5-5/whats-new-haiku-5-5 — подсистема: resources/model-quirks.json, оркестрация (дешёвые субагенты)
4. DeepSeek V4.1 Flash-1017 как дешёвое второе мнение — https://api-docs.deepseek.com/updates/ — подсистема: совет моделей, .vibe/providers/
5. Gemini 4 Argon GA и вывод gemini-2.5-* (20.10) — https://ai.google.dev/gemini-api/docs/changelog — подсистема: .vibe/providers/ (protocol gemini), чистка устаревших id
6. Шаг «AI-ревьюер» в пайплайне и pre-commit хук по мотивам Claude Code Review GA (общий сид VibeBrains — результат применим и к VibeIDEA) — https://releasebot.io/updates/anthropic/claude — подсистема: .vibe/pipelines.json, .vibe/hooks.json
7. OpenCode Go Plus ($40) и новые пути endpoint'ов как резервный провайдер — https://www.bitdoze.com/opencode-go-plan/ — подсистема: .vibe/providers/
8. Пуш «агенту нужно внимание / задача завершена» по мотивам JetBrains AI Assistant — https://plugins.jetbrains.com/plugin/22282-jetbrains-ai-assistant — подсистема: Telegram-мост, детектор петель, dead man's switch
9. CVE-2026-104850 (MCP TS SDK, подмена authorization server; фикс 1.31.0 / client 2.2.0, expectedIssuer) — https://advisories.gitlab.com/npm/@modelcontextprotocol/sdk/CVE-2026-104850/ — подсистема: MCP-слой и gateway (security)
10. Свежий адаптер claude-agent-acp (обновлён 21.10) — https://github.com/agentclientprotocol — подсистема: ACP-хост, .vibe/agents.json

Для каждой темы: 2–5 находок со ссылками; строка «→ VibeIDE:» — что именно забрать и метка трудоёмкости («конфиг» / «PR в quirks» / «фича»); если не применимо или уже реализовано после проверки репозитория — причина одной строкой. Для тем с пометкой «общий сид VibeBrains» результат оформи как файл(ы) для submodule VibeBrains — он общий с VibeIDEA.

Итог: backlog-таблица «задача | подсистема | трудоёмкость | польза | приоритет», отсортированная по польза/трудоёмкость. Только подтверждённое источниками; слухи помечай как слухи.
```

## Промпт для исследования под VibeIDEA

```
Проведи глубокое исследование по темам ниже (web_search + web_fetch источников) и оцени каждую на применимость к VibeIDEA — форку intellij-community (Kotlin/JVM, сборка Bazel; свой код только в модулях vibe-* / пакетах com.vibe.*, платформа правится точечно и под запись в FORK_CHANGES.md). Подсистемы: плагин vibe-agent (ACP-клиент к чужим агентам, провайдеры моделей в providers/, диагностика в doctor/, сеялка дефолтов VibeDefaults); общие сиды .vibe как git submodule VibeBrains (skills, prompts, workflows, пайплайны ролей, хуки, команды, правила — общие с VibeIDE); LSP-слой для TypeScript и PHP; MCP; каталог i18n с храповиком; токены темы Vibe.*; гейты checkVibeUi/I18n/Branding/Docs/Dist. Обязательно загляни в репозиторий, в docs/vibe/functional.md и docs/vibe/roadmap.md, чтобы не предлагать уже реализованное или уже отклонённое (docs/vibe/decisions.md).

Темы (из дайджеста за 09.10–21.10.2026):
1. MiniMax M3.1 Flash: новый id в дефолтном провайдере + проверка в doctor/ «endpoint рапортует контекст меньше заявленного» — https://platform.minimax.io/docs/release-notes/models , https://github.com/MiniMax-AI/MiniMax-M2.7/issues/46 — подсистема: vibe-agent providers/, VibeDefaults, doctor/ (строки через i18n)
2. API-кредиты Claude Max/Team: провайдер anthropic с ключом из Console и понятная диагностика ошибки «credit balance too low» — https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers — подсистема: vibe-agent providers/, doctor/
3. Claude Haiku 5.5: квирк sampling/prefill/effort и проверка в doctor/ — https://platform.claude.com/docs/ja/models/haiku-5-5/whats-new-haiku-5-5 — подсистема: vibe-agent providers/
4. Gemini 4 Argon GA, вывод gemini-2.5-* — https://ai.google.dev/gemini-api/docs/changelog — подсистема: vibe-agent providers/, doctor/
5. OpenCode Go Plus и новые пути endpoint'ов — https://www.bitdoze.com/opencode-go-plan/ — подсистема: vibe-agent providers/
6. Уведомления о статусе ACP-сессии (нужно внимание / завершено) по мотивам JetBrains AI Assistant — https://plugins.jetbrains.com/plugin/22282-jetbrains-ai-assistant — подсистема: vibe-agent (NotificationGroup, i18n)
7. Проверка issuer в OAuth MCP-клиента по мотивам CVE-2026-104850 — https://advisories.gitlab.com/npm/@modelcontextprotocol/sdk/CVE-2026-104850/ — подсистема: MCP (security)
8. Обновлённый Kotlin ACP SDK и адаптер claude-agent-acp (21.10) — https://github.com/agentclientprotocol — подсистема: vibe-agent ACP-клиент
9. IntelliJ Platform 2026.3 (EAP 6/7): вынесенные модули → bundledModule/bundledPlugin в Bazel, JnaLoader.load, Sdk extends UserDataHolderEx — https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html — подсистема: сборка Bazel модулей vibe-*, Plugin Verifier

Общие сиды (исследуются в промпте VibeIDE, здесь не дублировать): шаг «AI-ревьюер» в пайплайне ролей + pre-commit хук (по мотивам Claude Code Review GA).

Для каждой темы: 2–5 находок со ссылками; строка «→ VibeIDEA:» — что именно забрать и метка трудоёмкости («сид» / «конфиг» / «фича» / «патч платформы»); для «патча платформы» отдельно обоснуй, почему это нельзя сделать через extension point. Если не применимо или уже реализовано после проверки репозитория — причина одной строкой.

Итог: backlog-таблица «задача | подсистема | трудоёмкость | польза | приоритет», отсортированная по польза/трудоёмкость. Только подтверждённое источниками; слухи помечай как слухи.
```
