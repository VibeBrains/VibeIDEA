// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.impl.SnapshotBuildNumber
import org.jetbrains.intellij.build.VibeBuildNumber
import org.jetbrains.intellij.build.VibeIdeaProperties
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext

@ApiStatus.Internal
object VibeIdeaInstallersBuildTarget {
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking(Dispatchers.Default) {
      // Upstream drops the Windows installer step when the build runs on macOS («generally not
      // needed» — OpenSourceCommunityInstallersBuildTarget): IDEA CE has no .exe as a product. We
      // need it. Since 0.5.0 Windows is built from this Mac (owner's decision 10.09.2026), and a
      // person on Windows installs an .exe rather than unpacking an archive. NSIS cross-builds:
      // the pinned NSIS archive carries makensis-mac-aarch64 (manuals/release.md).
      val base = OpenSourceCommunityInstallersBuildTarget.OPTIONS
      val options = base.copy(buildStepsToSkip = base.buildStepsToSkip - BuildOptions.WINDOWS_EXE_INSTALLER_STEP)
      // A real build number unless one was passed explicitly: a SNAPSHOT distribution considers
      // itself newer than anything an update channel could publish (see VibeBuildNumber).
      if (System.getProperty("build.number") == null) {
        val appInfo = COMMUNITY_ROOT.communityRoot.resolve("vibeidea-customization/resources/idea/VibeIdeaApplicationInfo.xml")
        options.buildNumber = VibeBuildNumber.of(
          productVersion = VibeBuildNumber.productVersion(java.nio.file.Files.readString(appInfo)),
          platformLine = VibeBuildNumber.platformLine(SnapshotBuildNumber.VALUE),
        )
      }
      val context = createBuildContext(
        projectHome = COMMUNITY_ROOT.communityRoot,
        productProperties = VibeIdeaProperties(COMMUNITY_ROOT.communityRoot),
        setupTracer = true,
        options = options,
      )
      context.compileModules(moduleNames = null)
      buildDistributions(context)
    }
  }
}
