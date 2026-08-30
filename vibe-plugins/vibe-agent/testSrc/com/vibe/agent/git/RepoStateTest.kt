// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepoStateTest {
  private val labels = object : RepoState.Labels {
    override fun header(branch: String?, upstream: String?, ahead: Int, behind: Int, detached: Boolean) =
      "ветка: ${branch ?: if (detached) "detached" else "?"} ↑$ahead ↓$behind"
    override val clean = "чисто"
    override fun change(change: RepoState.Change) =
      "  ${change.status} ${change.path} +${change.added} -${change.removed}${if (change.binary) " bin" else ""}"
    override fun more(count: Int) = "  … ещё $count"
    override val commitsHeader = "коммиты:"
  }

  @Test
  fun `the branch line yields branch, upstream and divergence`() {
    val state = RepoState.parseBranchLine("main...origin/main [ahead 2, behind 1]")
    assertEquals("main", state.branch)
    assertEquals("origin/main", state.upstream)
    assertEquals(2, state.ahead)
    assertEquals(1, state.behind)
  }

  @Test
  fun `a branch without an upstream is not a parse failure`() {
    val state = RepoState.parseBranchLine("next")
    assertEquals("next", state.branch)
    assertNull(state.upstream)
    assertEquals(0, state.ahead)
  }

  @Test
  fun `a detached HEAD is reported as detached rather than as a branch named HEAD`() {
    val state = RepoState.parseBranchLine("HEAD (no branch)")
    assertTrue(state.detached)
    assertNull(state.branch)
  }

  @Test
  fun `status lines are parsed with their two-letter codes`() {
    val (branch, changes) = RepoState.parseStatus("## next\n M src/Main.kt\nA  src/New.kt\n?? scratch.txt\n")
    assertEquals("next", branch)
    assertEquals(listOf("src/Main.kt", "src/New.kt", "scratch.txt"), changes.map { it.path })
    assertFalse(changes[0].staged)
    assertTrue(changes[1].staged)
    assertTrue(changes[2].untracked)
  }

  @Test
  fun `a rename keeps the name the file has now`() {
    // «old -> new»: pointing the agent at the old path sends it to read a file that is gone.
    val (_, changes) = RepoState.parseStatus("## main\nR  old/Name.kt -> new/Name.kt\n")
    assertEquals("new/Name.kt", changes.single().path)
  }

  @Test
  fun `numstat sizes are attached to the matching files`() {
    val state = RepoState.assemble("## main\n M a.kt\n M b.kt\n", "10\t2\ta.kt\n1\t1\tb.kt\n", "")
    val a = state.changes.first { it.path == "a.kt" }
    assertEquals(10, a.added)
    assertEquals(2, a.removed)
  }

  @Test
  fun `a binary file is marked instead of being counted as zero changes`() {
    // git prints «- -» for binaries; zero would read as «ничего не изменилось».
    val state = RepoState.assemble("## main\n M logo.png\n", "-\t-\tlogo.png\n", "")
    assertTrue(state.changes.single().binary)
  }

  @Test
  fun `a renamed file in numstat is matched by its destination`() {
    val state = RepoState.assemble("## main\nR  a.kt -> b.kt\n", "3\t1\t{a => b}.kt\n5\t0\tb.kt\n", "")
    assertEquals(5, state.changes.single().added)
  }

  @Test
  fun `the log becomes hash plus subject`() {
    val commits = RepoState.parseLog("abc1234 fix(гейт): описание\ndef5678 docs: правки\n")
    assertEquals("abc1234", commits[0].hash)
    assertEquals("fix(гейт): описание", commits[0].subject)
    assertEquals(2, commits.size)
  }

  @Test
  fun `a clean repository says so instead of printing an empty list`() {
    val report = RepoState.report(RepoState.State(branch = "main", upstream = null), limit = 10, labels = labels)
    assertTrue(report.contains("чисто"))
  }

  @Test
  fun `the report is sorted by size, not by name`() {
    // «что тут произошло» is answered by the biggest files, never by the alphabet.
    val state = RepoState.assemble("## main\n M zebra.kt\n M alpha.kt\n", "100\t0\tzebra.kt\n1\t0\talpha.kt\n", "")
    val report = RepoState.report(state, limit = 10, labels = labels)
    assertTrue(report.indexOf("zebra.kt") < report.indexOf("alpha.kt"))
  }

  @Test
  fun `the cap is announced rather than applied silently`() {
    val many = (1..30).joinToString("\n") { " M file$it.kt" }
    val state = RepoState.assemble("## main\n$many\n", "", "")
    val report = RepoState.report(state, limit = 5, labels = labels)
    assertTrue(report.contains("… ещё 25"))
  }

  @Test
  fun `garbage does not crash the parsers`() {
    assertTrue(RepoState.parseStatus("").second.isEmpty())
    assertTrue(RepoState.parseNumstat("мусор без табов").isEmpty())
    assertTrue(RepoState.parseLog("").isEmpty())
    assertNull(RepoState.parseBranchLine(null).branch)
  }
}
