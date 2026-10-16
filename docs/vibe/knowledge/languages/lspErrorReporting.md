# Сбои запросов языковых серверов: в журнал, а не всплывашкой

## Симптом

Фоновый запрос, на который сервер не смог ответить (ESLint на файле без конфига, файл исчез с диска), показывался всплывашкой с кнопкой «Show Logs».
В VS Code такое остаётся в журнале.

## Что выяснено по байткоду LSP4IJ 0.20.2

- `CancellationSupport.getReportErrorKind` берёт `ProjectLanguageServerSettings.getLanguageServerSettings(id).getErrorReportingKind()`.
- Нет записи или вид пуст — `ErrorReportingKind.getDefaultValue()`, а он жёстко `as_notification`.
- В `LanguageServerFactory`, `LSPClientFeatures` и определении сервера такого умолчания нет, переопределить его там нельзя.
- Значения: `none`, `as_notification`, `in_log`.

## Решение

`VibeErrorReporting` — `postStartupActivity` в `vibe-lsp4ij-integration.xml`.
Для каждого id из `BranchSwitchRefresh.SERVER_IDS` пишет `in_log`, но только если записи нет (`updateSettings`) или вид в ней пуст (сеттер).
Выбор человека в странице сервера хранится явным значением, поэтому не перезаписывается; вернуть всплывашки можно, выбрав «As notification».

## Журнал

«Show Logs» вызывает `LSPConsoleToolWindowPanel.selectLogTab` окна «Language Servers».
`LanguageClientImpl.logMessage` пишет в консоль сервера без проверки трассировки (`ServerTrace` относится к вкладке протокола), так что отдельно включать запись для наших серверов не нужно.
Вывод из байткода, на живой IDE не прогнан.

## Гейт

`ErrorReportingDefaultTest`: пустая настройка становится `in_log` у всех серверов, явные `as_notification` и `none` целы, прочие поля записи не затираются, список засева совпадает с серверами дескриптора.
