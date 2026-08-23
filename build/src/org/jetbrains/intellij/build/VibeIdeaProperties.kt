// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import kotlinx.collections.immutable.persistentListOf
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
      .removing("intellij.featuresTrainer") + persistentListOf("intellij.javaFX.community")
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
  }

  override fun createWindowsCustomizer(projectHome: Path): WindowsDistributionCustomizer = ideaCommunityWindowsCustomizer(projectHome) {
    fullName { _ -> "VibeIDEA" }
    installDirNameHandler { _ -> "VibeIDEA" }
  }

  override fun createLinuxCustomizer(projectHome: Path): LinuxDistributionCustomizer = ideaCommunityLinuxCustomizer(projectHome) {
    rootDirectoryName { _, _ -> "vibeidea" }
  }
}
