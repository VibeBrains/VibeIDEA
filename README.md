# VibeIDEA

Открытая IDE PhpStorm-класса со встроенным агентом. Форк
[intellij-community](https://github.com/JetBrains/intellij-community): платформа JetBrains, языки
через LSP, вся агентская обвязка — своя.

Родственный продукт — [VibeIDE](https://github.com/VibeBrains/VibeIDE), то же самое на базе VS Code.
Общий набор окружения `.vibe` у них один на двоих
([VibeBrains](https://github.com/VibeBrains/VibeBrains)).

> Статус: рабочая сборка, первый публичный релиз. Инсталляторы собираются локально —
> см. [Сборка](#сборка).

## Что умеет

**Языки из коробки.** PHP, TypeScript/JavaScript, CSS/SCSS/LESS и ESLint работают сразу после
установки: серверы едут вместе с IDE (Phpactor — phar, остальные — подготовленное npm-дерево), а
рантаймы берутся с машины (PHP и Node). Свой сервер, если он у вас установлен, **всегда сильнее
встроенного** — проект, прибитый к другой версии, не ломается о нашу.

**Отладка из коробки.** Адаптеры vscode-js-debug и vscode-php-debug едут с IDE, а конфигурацию запуска — команду
адаптера, JSON и признак готовности — заводит сама IDE: точку останова поставить и нажать «Debug».

**Агент вместо чата.** ACP-клиент к любому локальному агенту (Claude Code, Codex, Gemini CLI) плюс
прямая работа с моделями по своим ключам. Планы переживают перезапуск IDE, ходы пишутся в журнал,
расход считается по ролям, целям и по файлам, уехавшим в контекст.

**Предохранители, а не обещания.** Детектор петель, страховка молчания, детектор застоя (ходы идут —
файлы не меняются), предохранитель повторных таймаутов и «N отказов из последних M». Каждый называет
причину словами и останавливает ход, а не пишет в лог.

**Дизайн-детектор.** 81 правило по живой странице в двух вьюпортах: пол качества (контраст, зоны
нажатия, обрезанное содержимое, формы, доступность) и вкус (приметы сгенерированного вида,
типографика, ритм, цвет, движение). Каждая находка приходит с селектором и измеренным значением —
её оспаривают числом, а не мнением.

**Мост в Telegram.** Задача с телефона, сводка за день, подтверждение разрушительных команд кнопками
и голосовые сообщения (расшифровка локальным whisper, если он на машине есть).

Полный каталог возможностей — [docs/vibe/functional.md](docs/vibe/functional.md).

## Установка

Собранные образы — `.dmg` для macOS (Apple Silicon) и `.exe`/`.win.zip` для Windows (x64) — во вкладке [Releases](https://github.com/VibeBrains/VibeIDEA/releases).

Что нужно на машине, чтобы всё работало из коробки:

| Для чего | Что поставить |
|---|---|
| PHP | PHP 8.1+ (`brew install php`) |
| TypeScript, CSS, ESLint | Node 18+ (`brew install node`) |
| `/watch`, голосовые | `yt-dlp`, `ffmpeg`, `whisper` — по желанию |
| PDF-вложения | `pdftotext` (`brew install poppler`) — по желанию |

Рантаймы мы не поставляем намеренно: второй интерпретатор в дистрибутиве — это второе, что придётся
обновлять при каждой его уязвимости. Чего не хватает, IDE говорит словами и предлагает поставить
кнопкой.

## Сборка

Тулчейн герметичный: JDK и Bazel скачиваются сами.

```shell
git clone --recurse-submodules https://github.com/VibeBrains/VibeIDEA.git
cd VibeIDEA
./getPlugins.sh                       # клон android/, нужен инсталлятору
sh vibe-plugins/deps/download.sh      # LSP4IJ и языковые серверы по закреплённым версиям
./vibeidea-installers.cmd -Dintellij.build.target.os=current -Dintellij.build.target.arch=current
```

Готовые артефакты — в `out/vibeidea/artifacts/`. Запуск из исходников:
`./bazel.cmd run //build:idea_community`.

Проверки перед коммитом — четыре гейта в `vibe-plugins/tools/`: тонкие скроллы, локализация,
брендинг, целостность документации; их же гоняет CI на каждый push и PR. После сборки инсталлятора
— пятый, `checkVibeDist.sh`: плагины в индексе, библиотеки и серверы на месте и запускаются.
Раз в неделю CI дополнительно считает отставание от апстрима и проверяет поставляемые серверы на
уязвимости.

## Документация

- [docs/vibe/functional.md](docs/vibe/functional.md) — что умеет продукт, одна запись на возможность;
- [docs/vibe/manuals/](docs/vibe/README.md) — как это делать: языковые серверы, отладка, форматы
  конфигов, сравнение моделей;
- [docs/vibe/knowledge/](docs/vibe/knowledge/README.md) — база знаний: грабли, на которые мы уже
  наступили, с разбором;
- [docs/vibe/roadmap.md](docs/vibe/roadmap.md) — что сделано и что впереди;
- [FORK_CHANGES.md](FORK_CHANGES.md) — каждое отличие от апстрима, с причиной.

Апстримовская инструкция по сборке платформы сохранена как
[README.upstream.md](README.upstream.md).

## Связь и поддержка

**GitHub Issues** — для воспроизводимых сбоев, регрессий и предложений по продукту:
[завести issue](https://github.com/VibeBrains/VibeIDEA/issues/new). Так задача не потеряется, к ней
можно приложить логи и версию сборки, а исправление будет привязано к релизу.

### Поддержать проект

Если VibeIDEA оказался полезным — буду рад благодарности 🙏

<a href="media/QR-Code.jpg" target="_blank" rel="noopener noreferrer">
  <img src="media/QR-Code.jpg" width="120" alt="QR-код для поддержки проекта" />
</a>

## Лицензия

Исходники — Apache 2.0: см. [vibe-plugins/legal/LICENSE.txt](vibe-plugins/legal/LICENSE.txt), этот
же файл едет в дистрибутиве.

VibeIDEA построен на базе [intellij-community](https://github.com/JetBrains/intellij-community),
который также распространяется под Apache 2.0. **VibeIDEA не является продуктом JetBrains**, и
условия JetBrains для их собственных сборок ([LICENSE.txt](LICENSE.txt) в корне репозитория —
апстримовский файл) на эту сборку не распространяются: они описывают продукты, которые JetBrains
распространяет под именами IntelliJ IDEA и PyCharm, вместе с их телеметрией и учётными записями.
Наша сборка **не отправляет данные об использовании** в VibeBrains.

Сторонние компоненты перечислены в `license/third-party-libraries.html` внутри дистрибутива. Из
крупного: JetBrains Runtime (GPLv2 + Classpath Exception), LSP4IJ (EPL 2.0), Phpactor, vtsls и
vscode-langservers-extracted (MIT).
