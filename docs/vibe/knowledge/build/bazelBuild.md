# Сборка intellij-community (Bazel)
← [Knowledge Index](../README.md)

## [инструмент] Герметичный Bazel-тулчейн: ничего не ставить руками

**Контекст:** разведка 2026-08-23 по README master и build-скриптам IC (веб, до первого локального прогона). Проект мигрирует на Bazel (миграция не завершена — часть тестов через чистый Bazel не бегает); «Building the project using only IDE built-in capabilities is not supported anymore».

**Суть:**
- JDK/Bazel вручную НЕ ставятся: `./bazel.cmd` сам качает bazelisk (версия в `.bazeliskversion`) → форк Bazel JetBrains (`.bazelversion`); тулчейн-JDK — JetBrains Runtime (`build/jbr-toolchains.bzl`), есть артефакт osx-aarch64.
- **Android-репо обязателен для инсталлятора даже без интереса к Android**: `IdeaCommunityProperties` бандлит android-плагин → без каталога `android/` сборка инсталлятора падает. `./getPlugins.sh` (есть `--shallow`); зеркало: `https://github.com/JetBrains/android.git`. IC и android чекаутить на одинаковые ветки/теги.
- Ресурсы: CI дословно — «The IDE build may hit OOM (exit 137) with the default 16 GB RAM and 4 GB swap», CI добавляет 30 GB swap; диск на полный цикл — закладывать ~100 GB; CI-прогон инсталляторов ~60–68 мин на 4 ядрах.

**Применение:**
- Запуск IDE из исходников (без инсталлятора): `./bazel.cmd run //build:idea_community`
- Инсталляторы текущей ОС: `./installers.cmd -Dintellij.build.target.os=current` (неподписанные dmg/sit на macOS; артефакты в `out/idea-ce/artifacts`); опции — `platform/build-scripts/.../BuildOptions.kt`.
- Тесты: `./tests.cmd -Dintellij.build.test.patterns=<класс>` (часть тестов через чистый Bazel не бегает — официально советуют tests.cmd).
- Разработка в IDE: IDEA 2026.1+ с Bazel-плагином, открывать `.bazelproject`.
- На машине с 16 GB RAM / <50 GB свободного диска полный цикл инсталляторов не гонять; для смока достаточно `./bazel.cmd run //build:idea_community` с контролем свободного места.

**Антипаттерны:** ставить свой JDK/Bazel «для надёжности» — бесполезно и вводит в заблуждение: тулчейн качает свои версии и внешние не использует; jps-bootstrap как путь сборки (легаси, installers.cmd его больше не использует).
