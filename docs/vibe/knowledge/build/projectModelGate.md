# Ссылка в `.iml` проверяется только сборкой инсталлятора

**Роль:** [грабли+гейт] · **Найдено:** 07.09.2026, на фазе 1 выпуска 0.4.2

## Контекст

Модулю `vibe-lsp` понадобились Gson и LSP4J (настройки для сервера ESLint). Зависимость была
добавлена в `BUILD.bazel` — и в `.iml`, но там я написал её как **библиотеку уровня проекта**
(`<orderEntry type="library" name="gson" level="project"/>`), какой в модели проекта нет: в дереве
IC это модули-обёртки `intellij.libraries.gson` и `intellij.libraries.eclipse.lsp4j`.

Дальше произошло самое неприятное: **всё было зелёным**. Модуль собирался, 1530 тестов проходили,
четыре гейта молчали. Сборка инсталлятора упала через двадцать секунд после старта:

```
Exception in thread "main" java.lang.IllegalStateException:
library dependency 'lib dep [lib ref: 'gson' in project ref]' from module intellij.vibe.lsp is not resolved
```

Причина в том, что `bazel build`/`bazel test` читают граф Bazel и до JPS-модели не доходят, а
генератор `bazel-targets.json` обходит **всю** модель проекта и падает на первой неразрешённой
ссылке.

Это третий случай из одного семейства — «состав нашего кода описан в двух местах» (см.
[threePackagingSystems.md](threePackagingSystems.md)).

## Суть

Правильные написания — модули-обёртки, а не библиотеки:

```xml
<orderEntry type="module" module-name="intellij.libraries.gson" />
<orderEntry type="module" module-name="intellij.libraries.eclipse.lsp4j" />
```

Класс ошибки закрыт гейтом `./vibe-plugins/tools/checkVibeModules.sh`: он читает `.iml` наших
плагинов и требует, чтобы каждая библиотека уровня проекта нашлась в `.idea/libraries`, а каждый
модуль — в дереве (`.iml` или `module_name` в `BUILD.bazel`). Гейт проверен поломкой: возвращённое
`name="gson"` его валит.

## Применение

- Добавили зависимость нашему модулю — правьте **оба** места и прогоняйте `checkVibeModules.sh`.
  Гейт стоит секунды, сборка инсталлятора — двадцать минут.
- Имя библиотеки ищите не по догадке: `grep -rn "имя" --include="*.iml" .` покажет, как её
  объявляют соседние модули дерева.
- Симптом «зелёные тесты, падающая сборка» на этом проекте почти всегда означает расхождение
  моделей, а не поломку кода.
