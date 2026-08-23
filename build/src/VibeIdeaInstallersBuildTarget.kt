// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.VibeIdeaProperties
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext

@ApiStatus.Internal
object VibeIdeaInstallersBuildTarget {
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking(Dispatchers.Default) {
      val context = createBuildContext(
        projectHome = COMMUNITY_ROOT.communityRoot,
        productProperties = VibeIdeaProperties(COMMUNITY_ROOT.communityRoot),
        setupTracer = true,
        options = OpenSourceCommunityInstallersBuildTarget.OPTIONS.copy(),
      )
      context.compileModules(moduleNames = null)
      buildDistributions(context)
    }
  }
}
