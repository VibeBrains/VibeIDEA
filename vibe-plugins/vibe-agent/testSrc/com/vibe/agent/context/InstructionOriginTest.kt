// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstructionOriginTest {
  @Test
  fun `directories above the root stop at the home folder`() {
    // A file in the home directory belongs to every project at once, so it is never this project's instruction.
    val above = InstructionOrigin.ancestors(Path.of("/home/me/work/proj"), Path.of("/home/me"))
    assertEquals(listOf(Path.of("/home/me/work")), above)
  }

  @Test
  fun `without a home folder the filesystem root is still not reported`() {
    val above = InstructionOrigin.ancestors(Path.of("/home/me/work/proj"), null)
    assertEquals(listOf(Path.of("/home/me/work"), Path.of("/home/me"), Path.of("/home")), above)
    assertFalse(Path.of("/") in above)
  }

  @Test
  fun `a project directly in the home folder has nothing above it that counts`() {
    assertEquals(emptyList(), InstructionOrigin.ancestors(Path.of("/home/me/proj"), Path.of("/home/me")))
  }

  @Test
  fun `git output is split on NUL and only instruction files are kept`() {
    val tracked = InstructionOrigin.parseTracked("AGENTS.md\u0000src/CLAUDE.md\u0000src/Main.kt\u0000docs\\AGENTS.md\u0000")
    assertEquals(setOf("AGENTS.md", "src/CLAUDE.md", "docs/AGENTS.md"), tracked)
  }

  @Test
  fun `an untracked file is a warning and a tracked one is not`() {
    val found = InstructionOrigin.findings(
      listOf("AGENTS.md", "pkg/AGENTS.md"),
      tracked = setOf("AGENTS.md"),
      above = emptyList(),
    )
    val warnings = InstructionOrigin.warnings(found)
    assertEquals(listOf("pkg/AGENTS.md"), warnings.map { it.path })
    assertEquals(false, warnings.single().tracked)
  }

  @Test
  fun `git saying nothing is not the same as git saying untracked`() {
    // A project outside a repository would otherwise report every instruction file it has.
    val found = InstructionOrigin.findings(listOf("AGENTS.md"), tracked = null, above = emptyList())
    assertEquals(emptyList(), InstructionOrigin.warnings(found))
    assertNull(found.single().tracked)
  }

  @Test
  fun `a file above the root is always named`() {
    val found = InstructionOrigin.findings(emptyList(), tracked = setOf("AGENTS.md"), above = listOf("/home/me/work/AGENTS.md"))
    val warnings = InstructionOrigin.warnings(found)
    assertEquals(InstructionOrigin.Scope.ABOVE, warnings.single().scope)
  }

  @Test
  fun `scan finds nested instructions and does not descend into dependency trees`() {
    val root = Files.createTempDirectory("vibe-instructions")
    Files.writeString(root.resolve("AGENTS.md"), "root")
    Files.createDirectories(root.resolve("pkg"))
    Files.writeString(root.resolve("pkg/CLAUDE.md"), "package")
    Files.createDirectories(root.resolve("node_modules/left-pad"))
    Files.writeString(root.resolve("node_modules/left-pad/AGENTS.md"), "arrived with a dependency")
    Files.writeString(root.resolve("README.md"), "not an instruction")

    assertEquals(listOf("AGENTS.md", "pkg/CLAUDE.md"), InstructionOrigin.scan(root))
  }

  @Test
  fun `a dependency directory is skipped by its own path, not only by a path inside it`() {
    // The walk prunes by the directory it is about to enter, and that path has no trailing file to match against.
    assertTrue(ProjectFiles.skips("node_modules"))
    assertTrue(ProjectFiles.skips("app/build"))
    assertTrue(ProjectFiles.skips(".git"))
    assertFalse(ProjectFiles.skips("distribution"))
    assertFalse(ProjectFiles.skips("AGENTS.md"))
  }
}
