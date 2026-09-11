// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The resolver on a real disk: the part of the path rules that cannot be proven without one.
 *
 * On macOS the temporary folder itself lives behind a symlink (`/var` → `/private/var`), so every test
 * here also proves that a root reached through a link is still recognised as itself.
 */
class AgentPathsTest {
  private fun resolved(raw: String): AgentPath = assertIs<AgentPaths.Result.Resolved>(AgentPaths.resolve(raw)).path

  private fun roots(project: Path) = AccessPolicy.Roots(projectBase = AgentPaths.physical(project.toAbsolutePath().normalize()).toString())

  /** Symbolic links need privileges on Windows; where they cannot be made, there is nothing to test. */
  private fun link(at: Path, to: Path): Path {
    val made = runCatching { Files.createSymbolicLink(at, to) }
    assumeTrue(made.isSuccess, "symbolic links are not available here")
    return at
  }

  @Test
  fun `a relative path is refused`() {
    assertEquals(AgentPaths.Result.Refused(AgentPaths.Refusal.NOT_ABSOLUTE), AgentPaths.resolve("src/Main.kt"))
  }

  @Test
  fun `dot-dot is resolved before anything else`(@TempDir tmp: Path) {
    val project = Files.createDirectories(tmp.resolve("app"))
    val path = resolved(project.resolve("src/../b.txt").toString())
    assertEquals(project.resolve("b.txt"), path.normalized)
    assertEquals(AccessPolicy.Access.READ_WRITE, AccessPolicy.of(path.canonical.toString(), roots(project)))
  }

  @Test
  fun `dot-dot out of the project lands outside and is denied`(@TempDir tmp: Path) {
    val project = Files.createDirectories(tmp.resolve("app"))
    Files.createDirectories(tmp.resolve("home/.ssh"))
    val path = resolved(project.resolve("../home/.ssh/id_rsa").toString())
    assertEquals(AccessPolicy.Access.DENIED, AccessPolicy.of(path.canonical.toString(), roots(project)))
  }

  @Test
  fun `a link inside the project that points outside is judged by where it points`(@TempDir tmp: Path) {
    val project = Files.createDirectories(tmp.resolve("app"))
    val outside = Files.createDirectories(tmp.resolve("outside"))
    link(project.resolve("docs"), outside)
    val path = resolved(project.resolve("docs/secret.txt").toString())
    assertEquals(outside.toRealPath().resolve("secret.txt"), path.canonical)
    assertEquals(AccessPolicy.Access.DENIED, AccessPolicy.of(path.canonical.toString(), roots(project)))
    assertEquals(project.resolve("docs/secret.txt"), path.normalized, "I/O keeps the agent's spelling")
  }

  @Test
  fun `a project reached through a link is still the project`(@TempDir tmp: Path) {
    val real = Files.createDirectories(tmp.resolve("real"))
    val alias = link(tmp.resolve("alias"), real)
    val path = resolved(alias.resolve("new/file.txt").toString())
    assertTrue(path.canonical.startsWith(real.toRealPath()), "canonical: ${path.canonical}")
    assertEquals(AccessPolicy.Access.READ_WRITE, AccessPolicy.of(path.canonical.toString(), roots(alias)))
  }

  @Test
  fun `a dangling link on the way is refused, not followed into a new file`(@TempDir tmp: Path) {
    val project = Files.createDirectories(tmp.resolve("app"))
    link(project.resolve("dl"), tmp.resolve("nowhere"))
    val expected = AgentPaths.Result.Refused(AgentPaths.Refusal.UNRESOLVABLE)
    assertEquals(expected, AgentPaths.resolve(project.resolve("dl").toString()), "the link itself")
    assertEquals(expected, AgentPaths.resolve(project.resolve("dl/file.txt").toString()), "a file behind it")
  }

  @Test
  fun `a path that does not exist yet keeps its tail`(@TempDir tmp: Path) {
    val project = Files.createDirectories(tmp.resolve("app"))
    val path = resolved(project.resolve("a/b/c.txt").toString())
    assertEquals(project.toRealPath().resolve("a/b/c.txt"), path.canonical)
  }

  @Test
  fun `on a case-insensitive disk an existing folder resolves to its own spelling`(@TempDir tmp: Path) {
    // The allow side compares exactly and relies on this: a differently cased spelling of an existing
    // folder must come back as the folder's own spelling, or it would be refused for nothing.
    Files.createDirectories(tmp.resolve("CaseProbe"))
    assumeTrue(Files.exists(tmp.resolve("caseprobe")), "case-sensitive disk: nothing to check")
    val path = resolved(tmp.resolve("caseprobe/new.txt").toString())
    assertEquals("CaseProbe", path.canonical.parent.fileName.toString())
  }
}
