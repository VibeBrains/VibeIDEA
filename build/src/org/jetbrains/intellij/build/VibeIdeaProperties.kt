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
      .removing("intellij.featuresTrainer") + persistentListOf("intellij.javaFX.community", "intellij.vibe.lsp", "intellij.vibe.agent", "intellij.vibe.theme", "intellij.vibe.server")

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

  /** Files that are DATA rather than plugins: the language servers we ship next to our own plugin. */
  override suspend fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {
    // Phpactor (MIT) as a single phar: PHP works out of the box for anyone who has PHP, which is
    // everyone who writes PHP. It lands next to our language plugin, and the search order at
    // runtime puts the user's own installation FIRST — a project pinned to another version must
    // not break against ours.
    val servers = context.paths.communityHomeDir.resolve("vibe-plugins/deps/extracted/servers")
    check(Files.isDirectory(servers)) { "Bundled servers dir not found: $servers — run vibe-plugins/deps/download.sh first" }
    copyDir(servers, targetDirectory.resolve("plugins/vibe-lsp/servers"))
  }

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String = "vibeIdea-$buildNumber"

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "VibeIdea${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

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
  }

  override fun createLinuxCustomizer(projectHome: Path): LinuxDistributionCustomizer = ideaCommunityLinuxCustomizer(projectHome) {
    rootDirectoryName { _, _ -> "vibeidea" }
  }
}
