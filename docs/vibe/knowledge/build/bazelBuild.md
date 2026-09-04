# Сборка intellij-community (Bazel)
← [Knowledge Index](../README.md)

## [инструмент] Герметичный Bazel-тулчейн: ничего не ставить руками

**Контекст:** разведка 2026-08-23 по README master и build-скриптам IC (веб, до первого локального прогона). Проект мигрирует на Bazel (миграция не завершена — часть тестов через чистый Bazel не бегает); «Building the project using only IDE built-in capabilities is not supported anymore».

**Суть:**
- JDK/Bazel вручную НЕ ставятся: `./bazel.cmd` сам качает bazelisk (версия в `.bazeliskversion`) → форк Bazel JetBrains (`.bazelversion`); тулчейн-JDK — JetBrains Runtime (`build/jbr-toolchains.bzl`), есть артефакт osx-aarch64.
- **Android-репо обязателен для ЛЮБОЙ bazel-цели, читающей JPS-модель проекта, даже без интереса к Android** — проверено фактом 2026-08-23: `./bazel.cmd build //build:idea_community` падает за 31 с на `FileNotFoundException: android/android-adb/intellij.android.adb.iml` ещё на фазе Loading (репозиторий `jps_dynamic_deps_community`, `build/jps_model.bzl:237`). `./getPlugins.sh` (есть `--shallow`) клонирует по `git://git.jetbrains.org` — надёжнее зеркало: `https://github.com/JetBrains/android.git`. IC и android чекаутить на одинаковые ветки/теги.
- Ресурсы: CI дословно — «The IDE build may hit OOM (exit 137) with the default 16 GB RAM and 4 GB swap», CI добавляет 30 GB swap; диск на полный цикл — закладывать ~100 GB; CI-прогон инсталляторов ~60–68 мин на 4 ядрах.

**Применение:**
- Запуск IDE из исходников (без инсталлятора): `./bazel.cmd run //build:idea_community`
- Инсталляторы текущей ОС: **наши** — `./vibeidea-installers.cmd` (цель `//build:vibeidea_installers`, артефакты в `out/vibeidea/artifacts`), апстримные — `./installers.cmd` (чистая IDEA CE, `out/idea-ce/artifacts`). Оба принимают `-Dintellij.build.target.os=current`; опции — `platform/build-scripts/.../BuildOptions.kt`.
- Тесты: `./tests.cmd -Dintellij.build.test.patterns=<класс>` (часть тестов через чистый Bazel не бегает — официально советуют tests.cmd).
- Разработка в IDE: IDEA 2026.1+ с Bazel-плагином, открывать `.bazelproject`.
- На машине с 16 GB RAM / <50 GB свободного диска полный цикл инсталляторов не гонять; для смока достаточно `./bazel.cmd run //build:idea_community` с контролем свободного места.

- **Инсталляторы (проверено 2026-08-23):** `./installers.cmd -Dintellij.build.target.os=current -Dintellij.build.target.arch=current` → `out/idea-ce/artifacts/` (dmg 783M + sit 813M + SBOM + sha256/512); на тёплом кэше после полной компиляции — ~5 мин. Лишние артефакты режутся свойством `intellij.build.skip.build.steps` (константы шагов — `BuildOptions.kt`, напр. `sources_archive`, `cross_platform_dist`, `non_bundled_plugins`). Подпись на macOS скипается автоматически → образ не подписан, запуск через Gatekeeper «Open Anyway». SPDX-ворнинги «GPL-2.0 is deprecated» — не фатальны.

**Антипаттерны:** ставить свой JDK/Bazel «для надёжности» — бесполезно и вводит в заблуждение: тулчейн качает свои версии и внешние не использует; jps-bootstrap как путь сборки (легаси, installers.cmd его больше не использует).

## Релизный флаг ApplicationInfo

`eap="false"` в `*ApplicationInfo.xml` требует `majorReleaseDate="YYYYMMDD"` на теге `<build>` — иначе инсталлятор падает в `ApplicationInfoPropertiesImpl`: «majorReleaseDate may be omitted only for EAP». Побочный эффект `eap="true"` — суффикс «-EAP» в имени бандла macOS (`MacDistributionBuilder.substitutePlaceholdersInInfoPlist`).


## Иконки дистрибутива: SVG ≠ Dock (проверено 2026-08-27)

- SVG из `<icon svg=…>` в ApplicationInfo работают только **внутри** приложения (About, welcome, заголовок). Иконку macOS-бандла (Dock, Finder) сборка берёт из **`.icns`** через `MacDistributionCustomizer.icnsPath`/`icnsPathForEAP`; без переопределения наследуется стоковый `build/idea-community-images/mac/product.icns` — брендированный дистрибутив уедет с чужим Dock-лого. Наш путь: `vibeidea-customization/resources/mac/vibeidea.icns`, прописан в `VibeIdeaProperties.createMacCustomizer` (путь резолвится относительно корня репо).
- `.icns` из SVG без внешних утилит: временная копия SVG с `width/height=1024` → `qlmanage -t -s 1024` → `sips -z` в размеры iconset (16…1024, для 16/32 — рендер упрощённого `*_16.svg`) → `iconutil -c icns`.
- **Сборка dmg требует ≥ ~10 ГБ свободного диска** сверх артефактов: `hdiutil create/convert` падает «на устройстве нет больше места» при ~6 ГБ. Быстрое лекарство: снести регенерируемое — `out/*/temp`, старые dmg/sit, `maven-artifacts`, чужой `out/idea-ce`.
- Брендированный инсталлятор — **только** `./vibeidea-installers.cmd` (→ `out/vibeidea/`); `./installers.cmd` собирает стоковую IntelliJ (→ `out/idea-ce/`) — легко перепутать, продукт внешне отличим лишь по product-info/иконке. Оба завершаются кодом 0 и дают валидный dmg на ~783 МБ, поэтому **ошибка не видна по выводу сборки** (наступлено 29.08.2026 повторно, при наличии этой самой записи — читать её ДО запуска). Единственная надёжная проверка, что в сборку попал наш код: `unzip -l out/vibeidea/dist.all/plugins/vibe-agent/lib/vibe-agent.jar | grep <НовыйКласс>`.
- **Без аргументов `vibeidea-installers.cmd` собирает ВСЕ ОС и обе архитектуры** — пять образов по ~800 МБ (dmg aarch64 + x64, win.zip ×2, tar.gz ×2), и dmg-шаг гарантированно упирается в диск (проверено 2026-08-28: упал `mac_dmg` при 8 ГБ свободных). Для локальной установки всегда: `./vibeidea-installers.cmd -Dintellij.build.target.os=current -Dintellij.build.target.arch=current` — один dmg, быстрее и втрое меньше диска.

## Сборка мимо `download.sh` шлёт в дистрибутив вчерашние зависимости (04.09.2026)

Гейт дистрибутива упал на «нет `phpactorLaunch.php` рядом с phar», хотя файл лежал в
`vibe-plugins/deps/` и был закоммичен. Причина не в сборке: я звал `vibeidea-installers.cmd`
напрямую, а раскладку `deps/extracted/servers` (её и копирует сборщик в дистрибутив) наполняет
**`download.sh`**. После merge чужой ветки, поменявшей набор файлов, каталог остался вчерашним:
старый `phpactorNoPosixCheck.php` на месте, нового лаунчера нет.

**Правило:** штатная точка входа — `./vibeidea-build.sh`, у неё шаг 1/3 это как раз `download.sh`.
Прямой вызов `vibeidea-installers.cmd` законен только тогда, когда зависимости заведомо свежие
(собирали минуту назад и `deps/` не менялся).

Поймал это не человек и не тест, а гейт дистрибутива — ровно тот случай, ради которого он
запускает файлы, а не проверяет их наличие в исходниках.
