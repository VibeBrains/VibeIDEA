# Настройки ESLint-сервера: три поля, на которых он падает

**Симптом (владелец, 12.09.2026, Windows-сборка 0.5.0).**
Открытие двух файлов дало три уведомления:
`ESLint: Request textDocument/codeAction failed with message: The "path" argument must be of type string. Received undefined`.
На macOS то же самое не воспроизводилось ни разу.

Отвечает на `workspace/configuration` наш `EslintLanguageClient`, и дефект был именно в его ответе.
Разобрано по коду поставляемого сервера
(`vibe-plugins/deps/extracted/servers/node/node_modules/vscode-langservers-extracted/lib/eslint-language-server/eslint.js`)
и проверено стендом, который поднимает этот сервер и повторяет реплику IDE.

## Что именно ломалось

**1. `workspaceFolder` мы не слали вовсе.**
Сервер выводит из него рабочий каталог: `settings.workspaceFolder !== undefined ? inferFilePath(...) : undefined`.
Без поля рабочий каталог остаётся `undefined`.

**2. `nodePath` мы слали пустой строкой.**
Пустая строка — это заданное значение, а не «нет значения»: условие `settings.nodePath !== null` проходит, и сервер идёт склеивать её с рабочим каталогом.
`path.join(undefined, "")` и есть то самое «Received undefined».
Правильное значение — `null`: оно означает «ищи node сам».

**3. `experimental.useFlatConfig: false` мы навязывали.**
ESLint 9 по умолчанию работает только на плоской конфигурации, а мы её принудительно выключали.

## Почему не воспроизводилось на macOS

Ветка склейки берётся не всегда, и порядок проверок пути на Windows другой.
Стенд на маке возвращал «codeAction ок» даже со старыми настройками — то есть отсутствие
воспроизведения на своей машине здесь ничего не доказывало, а доказал разбор кода сервера.

## Грабли при починке

**Убрать `experimental` совсем нельзя.**
Сервер читает `settings.experimental.useFlatConfig` без всякой проверки (строки 1263, 1285, 1331),
и на отсутствующем объекте падает уже по-новому:
`Cannot read properties of undefined (reading 'useFlatConfig')` — стенд поймал это сразу.
Правильная форма — **пустой объект**: разыменование проходит, а режим конфигурации сервер выбирает сам.

## Как проверять

Стенд — обычный stdio-клиент LSP на Node: поднимает наш сервер, отвечает на `workspace/configuration`
нашими настройками, ждёт `publishDiagnostics` и просит `textDocument/codeAction` **с реальной диагностикой**
(на пустом `context.diagnostics` сервер до опасного места может не дойти).
Прогонять надо на двух проектах: со старым `.eslintrc` и с плоским `eslint.config.mjs`.

Гейт в репозитории — `EslintSettingsTest`: он сторожит все три поля,
потому что снаружи их возврат выглядит ошибкой чужого сервера, а не нашей.
