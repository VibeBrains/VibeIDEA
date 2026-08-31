# Языковые серверы: TypeScript и PHP

Как включить полноценную работу с TypeScript и PHP в VibeIDEA. IDE несёт LSP-клиент (LSP4IJ) и плагин-регистратор из коробки; сами серверы — внешние программы, их надо поставить один раз.

## TypeScript — vtsls

Требуется Node.js ≥ 18.

```bash
npm install -g @vtsls/language-server
```

Проверка: `vtsls --version`. VibeIDEA ищет `vtsls` в PATH и типовых местах (`~/.npm-global/bin`, `/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin`).

## PHP — Phpactor

Требуется PHP ≥ 8.1.

```bash
mkdir -p ~/.local/bin && curl -Lo ~/.local/bin/phpactor https://github.com/phpactor/phpactor/releases/latest/download/phpactor.phar && chmod +x ~/.local/bin/phpactor
```

`~/.local/bin` вместо `/usr/local/bin` — чтобы не требовалось `sudo`; эта папка входит в список,
который IDE просматривает сама (GUI-приложение на macOS не наследует PATH оболочки).

**`brew install phpactor` не работает** — такой формулы нет, и мы сами раздавали эту команду до
31.08.2026. Команда, которая падает, хуже отсутствующей: человек делает вывод, что сломана вся
поддержка языка.

Проверка: `phpactor --version`. Ищется так же (плюс `~/.composer/vendor/bin`).

## Что получится

Открой `.ts`/`.tsx`/`.js` или `.php` файл — сервер стартует сам (LSP4IJ: консоль и статус — в тулвиндоу «Language Servers»). Работают: completion, диагностика, go to definition, find references, hover, форматирование. Подсветка — TextMate-грамматики из комплекта.

## Если сервер не стартует

Тулвиндоу «Language Servers» → выбрать сервер → вкладка Logs: в ошибке будет ровно то имя бинаря, которое не нашлось. Поставь его или пропиши абсолютный путь в Settings → Languages & Frameworks → Language Servers.

## Премиум-путь для PHP (по желанию)

Intelephense мощнее Phpactor (переименования, inlay hints — в платном tier), но его лицензия запрещает поставку в составе IDE — ставится самостоятельно: `npm i -g intelephense`, затем добавить сервер в LSP4IJ вручную (command: `intelephense --stdio`) и принять его EULA.
