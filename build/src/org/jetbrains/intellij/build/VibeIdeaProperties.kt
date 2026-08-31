// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import kotlinx.collections.immutable.persistentListOf
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
   * External artifacts bundled from pinned releases prepared by `vibe-plugins/deps/download.sh`:
   * LSP4IJ (EPL-2.0, never from JetBrains Marketplace) and the Phpactor phar (MIT).
   */
  override suspend fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {
    val lsp4ij = context.paths.communityHomeDir.resolve("vibe-plugins/deps/extracted/lsp4ij")
    check(Files.isDirectory(lsp4ij)) { "LSP4IJ plugin dir not found: $lsp4ij — run vibe-plugins/deps/download.sh first" }
    copyDir(lsp4ij, targetDirectory.resolve("plugins/lsp4ij"))

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
