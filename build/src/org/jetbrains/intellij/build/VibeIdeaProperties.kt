// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import kotlinx.collections.immutable.persistentListOf
import com.intellij.platform.buildScripts.licenses.LibraryLicense
import org.jetbrains.intellij.build.impl.PluginLayout
import org.jetbrains.intellij.build.io.copyDir
import java.nio.file.Files
import kotlinx.collections.immutable.plus
import java.nio.file.Path

/**
 * VibeIDEA — open-source IDE on top of the IntelliJ Community platform.
 * Kept as small as possible on top of [IdeaCommunityProperties] (same approach as [AndroidStudioProperties]);
 * every platform-file deviation must be recorded in FORK_CHANGES.md.
 */
open class VibeIdeaProperties(communityHomeDir: Path) : IdeaCommunityProperties(communityHomeDir) {
  init {
    platformPrefix = "VibeIdea"
    applicationInfoModule = "intellij.vibeidea.customization"
    useSplash = false
    buildCrossPlatformDistribution = false
    buildSourcesArchive = false

    productLayout.productImplementationModules += "intellij.vibeidea.customization"

    // Same trimming approach as AndroidStudioProperties: drop vendor AI/onboarding extras.
    productLayout.bundledPluginModules = IDEA_BUNDLED_PLUGINS
      .removing("intellij.mcpserver.plugin")
      .removing("intellij.featuresTrainer") + persistentListOf(
        "intellij.javaFX.community",
        // Task management the way PhpStorm has it: create a task, switch context, connect an issue
        // tracker. The platform ships only the Tasks&Contexts core; this is the plugin that adds
        // the part a person actually uses, and it is community code with a layout already written.
        "intellij.tasks.core",
        // Three more community plugins that sit in this repository unbundled while PhpStorm ships
        // them. A module without an explicit PluginLayout gets pluginAuto (DistributionJARsBuilder
        // :554), so a line each is the whole change.
        //   .env — in every PHP project; JetBrains took the plugin over and bundles it since
        //   PhpStorm 2024.3.2, and it stayed open source (MIT, plugins/env-files-support/LICENSE).
        "intellij.dotenv",
        //   XPath/XSLT — PhpStorm documents it as present; the layout is already written
        //   (CommunityRepositoryModules.kt:84).
        "intellij.xpath",
        //   JSONPath — what HTTP-client variables and JSON tooling are written against.
        "intellij.jsonpath",
        "intellij.vibe.lsp", "intellij.vibe.agent", "intellij.vibe.theme", "intellij.vibe.server", "intellij.vibe.http",
      )

    // Everything we bundle that is NOT a JPS library: the report is generated from library
    // dependencies, and a phar or an npm tree is invisible to it. Shipping someone else's MIT code
    // without naming it in the licence report is exactly the kind of quiet debt that surfaces at the
    // worst possible moment.
    allLibraryLicenses = allLibraryLicenses + listOf(
      LibraryLicense(name = "LSP4IJ", version = "0.20.1", attachedTo = "intellij.vibe.lsp",
                     url = "https://github.com/redhat-developer/lsp4ij")
        .eplV2("https://github.com/redhat-developer/lsp4ij/blob/main/LICENSE"),
      LibraryLicense(name = "Phpactor", version = "2026.06.23.0", attachedTo = "intellij.vibe.lsp",
                     url = "https://phpactor.readthedocs.io")
        .mit("https://github.com/phpactor/phpactor/blob/master/LICENSE"),
      LibraryLicense(name = "vtsls", version = "0.3.0", attachedTo = "intellij.vibe.lsp",
                     url = "https://github.com/yioneko/vtsls")
        .mit("https://github.com/yioneko/vtsls/blob/main/LICENSE"),
      LibraryLicense(name = "vscode-langservers-extracted", version = "4.10.0", attachedTo = "intellij.vibe.lsp",
                     url = "https://github.com/hrsh7th/vscode-langservers-extracted")
        .mit("https://github.com/hrsh7th/vscode-langservers-extracted/blob/master/LICENSE"),
      LibraryLicense(name = "vscode-js-debug", version = "1.117.0", attachedTo = "intellij.vibe.lsp",
                     url = "https://github.com/microsoft/vscode-js-debug")
        .mit("https://github.com/microsoft/vscode-js-debug/blob/main/LICENSE"),
      LibraryLicense(name = "vscode-php-debug", version = "1.40.1", attachedTo = "intellij.vibe.lsp",
                     url = "https://github.com/xdebug/vscode-php-debug")
        .mit("https://github.com/xdebug/vscode-php-debug/blob/main/LICENSE"),
    )

    productLayout.pluginLayouts = productLayout.pluginLayouts + persistentListOf(
      PluginLayout.pluginAuto("intellij.vibe.lsp") {},
      // The QR encoder rides INSIDE our plugin: the library module ships to the distribution only
      // when somebody's layout asks for it, and the first real dmg proved that a dependency in
      // BUILD.bazel (compilation) and in the .iml (project model) is not that somebody. Packaging is
      // decided here, and nowhere else.
      PluginLayout.pluginAuto("intellij.vibe.agent") { it.withModule("intellij.libraries.zxing.core") },
      PluginLayout.pluginAuto("intellij.vibe.theme") {},
      PluginLayout.pluginAuto("intellij.vibe.server") {},
      PluginLayout.pluginAuto("intellij.vibe.http") {},
    )
  }

  /**
   * LSP4IJ (EPL-2.0) as a PREBUILT PLUGIN, not as a copied directory.
   *
   * The difference is the whole feature. When `plugins/plugin-classpath.txt` exists — and it does in
   * every real distribution — the platform loads bundled plugins from that index and NOTHING else:
   * a directory copied into `plugins/` afterwards is invisible. That is exactly what happened for
   * every dmg until 31.08.2026: the log said «plugin com.redhat.devtools.lsp4ij is not resolved»,
   * our optional `<depends>` config was excluded, and TypeScript and PHP support silently did not
   * exist. Tests could not see it: they run against the module, not the installer.
   *
   * `getAdditionalPluginPaths` is the platform's own answer for prebuilt plugins: the build copies
   * the directory AND writes it into the index.
   */
  override suspend fun getAdditionalPluginPaths(context: BuildContext): List<Path> {
    val lsp4ij = context.paths.communityHomeDir.resolve("vibe-plugins/deps/extracted/lsp4ij")
    check(Files.isDirectory(lsp4ij)) { "LSP4IJ plugin dir not found: $lsp4ij — run vibe-plugins/deps/download.sh first" }
    return listOf(lsp4ij)
  }

  /**
   * Our own LICENSE, over the one the platform copies.
   *
   * The file the upstream build ships is JetBrains' «Open-Source Build Terms»: it governs the
   * builds JetBrains distributes under the names IntelliJ IDEA and PyCharm, and it describes their
   * telemetry, their accounts and their obligations. Shipping it inside VibeIDEA would tell our
   * users about a product they do not have. The SOURCES stay Apache 2.0 — that is what our file
   * says, together with what this build is actually made of.
   */
  override suspend fun copyAdditionalFiles(targetDir: Path, context: BuildContext) {
    super.copyAdditionalFiles(targetDir, context)
    val ours = context.paths.communityHomeDir.resolve("vibe-plugins/legal/LICENSE.txt")
    check(Files.isRegularFile(ours)) { "VibeIDEA LICENSE.txt not found: $ours" }
    Files.copy(ours, targetDir.resolve("LICENSE.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }

  /** Files that are DATA rather than plugins: the language servers we ship next to our own plugin. */
  override suspend fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {
    // Phpactor (MIT) as a single phar: PHP works out of the box for anyone who has PHP, which is
    // everyone who writes PHP. It lands next to our language plugin, and the search order at
    // runtime puts the user's own installation FIRST — a project pinned to another version must
    // not break against ours.
    val servers = context.paths.communityHomeDir.resolve("vibe-plugins/deps/extracted/servers")
    check(Files.isDirectory(servers)) { "Bundled servers dir not found: $servers — run vibe-plugins/deps/download.sh first" }
    // Фильтр мусора не косметика: сборщик в самом конце проверяет дистрибутив на «junk files» и
    // падает на `.DS_Store`, которые macOS насыпает в любую папку, открытую в Finder. Артефакт к
    // этому моменту уже создан — то есть неудачу легко принять за успех, а собранное окажется
    // непроверенным. Чинить в download.sh недостаточно: Finder создаёт их в любой момент после.
    copyDir(servers, targetDirectory.resolve("plugins/vibe-lsp/servers"), fileFilter = { path ->
      val name = path.fileName.toString()
      name != ".DS_Store" && !name.startsWith("._") && name != "Thumbs.db"
    })
  }

  /**
   * Имя артефакта несёт версию ПРОДУКТА, а не линию платформы.
   *
   * Было `vibeIdea-$buildNumber` — калька с апстримного `ideaIC-$buildNumber`, где номер сборки и
   * есть версия продукта. У форка это два разных числа, и скачанный `vibeIdea-263.SNAPSHOT.dmg`
   * не отвечал на единственный вопрос, который к имени файла и задают: какая это версия. Линия
   * платформы никуда не исчезает — она внутри, в номере сборки, и её печатает диагностика.
   *
   * Имя продукта — как он называется: `VibeIDEA-0.3.0.win.zip`. Строчное `vibeIdea` осталось от
   * апстримного `ideaIC`, где так пишется идентификатор, а не имя; в zip лежит портативная версия,
   * и человек видит это имя раньше всего остального.
   */
  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String =
    "VibeIDEA-" + appInfo.fullVersion

  /**
   * Каталог настроек НЕ привязан к версии — намеренно.
   *
   * Апстримная формула (`VibeIdea<major>.<minor>`) разводит настройки по версиям, и у JetBrains это
   * работает вместе с мастером переноса при первом запуске новой версии. У нас такого мастера нет,
   * поэтому та же формула означала бы «каждый выпуск начинается с чистых настроек» — человек
   * решил бы, что обновление стёрло его конфигурацию, и был бы прав по последствиям.
   *
   * Пока нет переноса — селектор стабильный. Когда появится (или когда сломается совместимость
   * настроек), сюда вернётся версия, и это будет осознанным решением, а не побочным эффектом
   * смены номера в другом файле.
   */
  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String = "VibeIdea"

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "vibeidea"

  override fun createMacCustomizer(projectHome: Path): MacDistributionCustomizer = ideaCommunityMacCustomizer(projectHome) {
    bundleIdentifier = "com.vibe.vibeidea"
    urlSchemes = listOf("vibeidea")
    rootDirectoryName { _, _ -> "VibeIDEA.app" }
    icnsPath = "vibeidea-customization/resources/mac/vibeidea.icns"
    icnsPathForEAP = "vibeidea-customization/resources/mac/vibeidea.icns"
  }

  override fun createWindowsCustomizer(projectHome: Path): WindowsDistributionCustomizer = ideaCommunityWindowsCustomizer(projectHome) {
    fullName { _ -> "VibeIDEA" }
    installDirNameHandler { _ -> "VibeIDEA" }
    // Same lesson as the macOS Dock icon: the SVG from ApplicationInfo lives INSIDE the app, while
    // the launcher, the taskbar and the installer take .ico/.bmp files — left unset, they inherit
    // the stock IntelliJ images. All four are generated from vibeidea.icns by
    // vibe-plugins/tools/makeWinImages.py.
    icoPath = "vibeidea-customization/resources/win/vibeidea.ico"
    icoPathForEAP = "vibeidea-customization/resources/win/vibeidea.ico"
    installerImagesPath = "vibeidea-customization/resources/win"
    // The inherited handler opens JetBrains' IDEA uninstall survey — a page about a product the
    // user does not have. No survey is better than someone else's.
    uninstallFeedbackUrl { _ -> null }
  }

  override fun createLinuxCustomizer(projectHome: Path): LinuxDistributionCustomizer = ideaCommunityLinuxCustomizer(projectHome) {
    rootDirectoryName { _, _ -> "vibeidea" }
  }
}
