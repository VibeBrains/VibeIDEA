# Как выпустить релиз

Порядок проверен на v0.1.0 (31.08.2026). Каждый шаг заканчивается фактом, а не намерением: если
шаг нечем подтвердить, релиз не готов.

## 1. Ветка

Повседневная работа идёт в `next`. Релиз — осознанное «выпускаем»: `main` догоняет `next`, и только
после этого ставится тег. Обратный порядок означал бы тег на том, что ещё может измениться.

```bash
git checkout main && git merge --ff-only next
```

## 2. Собрать и проверить дистрибутив

```bash
sh vibe-plugins/deps/download.sh      # LSP4IJ и языковые серверы по закреплённым версиям
./vibeidea-installers.cmd -Dintellij.build.target.os=current -Dintellij.build.target.arch=current
./vibe-plugins/tools/checkVibeDist.sh
```

Гейт дистрибутива обязателен: он проверяет то, чего не видит ни один юнит-тест — вписан ли готовый
плагин в индекс, доехали ли библиотеки внутрь плагина, **запускаются** ли встроенные серверы. За
один день 31.08.2026 четыре дефекта нашлись только здесь
([разбор](../knowledge/build/bundledPluginIndex.md)).

Перед сборкой — четыре гейта исходников и тесты, иначе выпускается непроверенное.

## 3. Заметки

Оформление — правило, а не вкус: разделы с эмодзи и живым заголовком, жирный зачин плюс проза,
точные шаги обхода неподписанной сборки, отдельный раздел «чего пока нет», блок поддержки. Подробно
— [agentsGuide.md, раздел 9](../agentsGuide.md), образец —
[releaseNotes-v0.1.0.md](../references/releaseNotes-v0.1.0.md).

Фраза поддержки берётся из пула [releaseDonationPhrases.md](../releaseDonationPhrases.md): выбранная
переезжает в «Историю использования» и удаляется из «Активных».

Контрольная сумма установщика идёт в заметки — скачавший должен иметь возможность проверить файл:

```bash
shasum -a 256 out/vibeidea/artifacts/vibeIdea-*-aarch64.dmg
```

## 4. Тег и публикация

```bash
git tag -a vX.Y.Z -m "VibeIDEA X.Y.Z — короткая суть"
git push origin main next vX.Y.Z
gh release create vX.Y.Z --repo VibeBrains/VibeIDEA --title "VibeIDEA X.Y.Z" --notes-file <файл>
gh release upload vX.Y.Z out/vibeidea/artifacts/vibeIdea-*-aarch64.dmg --repo VibeBrains/VibeIDEA
```

Загрузка 800 МБ идёт минуты; проверить, что файл на месте:

```bash
gh release view vX.Y.Z --repo VibeBrains/VibeIDEA --json assets --jq '.assets[] | {name, size, state}'
```

`state: uploaded` — единственное подтверждение. «Команда не выдала ошибку» им не является.

## 5. После публикации

- QR в заметках открыть и убедиться, что картинка отдаётся: ссылка обязана быть **абсолютной**
  (`raw.githubusercontent.com`), относительные пути работают в README и не работают в теле релиза.
- Заметки сохранить в `docs/vibe/references/releaseNotes-vX.Y.Z.md` — следующий релиз равняется на
  предыдущий, а не вспоминает.
- Пункт в [roadmap.md](../roadmap.md) со ссылкой на релиз.

## Права и учётки

Публикация делается из-под учётки, у которой есть **admin** на репозитории. У личного репозитория
(`type: User`) админ ровно один — владелец; ролей для коллабораторов там не существует, и «поднять
роль до Admin» невозможно в принципе. Коллаборатор с правом записи может пушить, но не может менять
ветку по умолчанию, описание и темы.

Если `gh` авторизован под другой учёткой — вход через устройство, секрет при этом не проходит через
чужие руки:

```bash
gh auth login --hostname github.com --git-protocol https --web --scopes repo
```

## Первая публикация форка

Проверить `ls .git/shallow` **до** пуша. Поверхностный клон сервер не примет —
`index-pack failed`; лечится пересборкой корня, рецепт в
[shallowClonePush.md](../knowledge/gitAndTools/shallowClonePush.md).
