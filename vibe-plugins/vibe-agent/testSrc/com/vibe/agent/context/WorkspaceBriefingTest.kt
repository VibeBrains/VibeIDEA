// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import com.vibe.agent.util.HumanDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The turn says where it is happening, and the silence says how long it has been silent.
 *
 * Both are the same defect seen twice: a number nobody computed. The model was left to work out the
 * project (and two endpoints of one vendor worked it out differently), and the watchdog was left to
 * subtract from a clock nobody had started.
 */
class WorkspaceBriefingTest {
  private val workspace = WorkspaceBriefing.Workspace(
    name = "VibeReel", root = "/Users/x/Projects/VibeReel", branch = "next", os = "Mac OS X")

  @Test
  fun `the briefing names the project, its root and its branch`() {
    val text = WorkspaceBriefing.text(workspace)
    assertTrue("VibeReel" in text, text)
    assertTrue("/Users/x/Projects/VibeReel" in text, text)
    assertTrue("next" in text, text)
    assertTrue("Mac OS X" in text, text)
  }

  @Test
  fun `the briefing says which tool is about the open project and which is about the store`() {
    // The whole point: with both tools offered, the two endpoints of one vendor chose differently,
    // and the Anthropic one asked the person to type the path.
    val text = WorkspaceBriefing.text(workspace)
    assertTrue("vibe_project_info" in text, text)
    assertTrue("project_resolve" in text, text)
  }

  @Test
  fun `a folder without a repository simply has no branch line`() {
    val text = WorkspaceBriefing.text(workspace.copy(branch = null))
    assertFalse("next" in text, text)
    assertTrue("VibeReel" in text, text)
  }

  @Test
  fun `the branch comes out of HEAD, slashes and all`() {
    assertEquals("next", WorkspaceBriefing.branchOfHead("ref: refs/heads/next\n"))
    assertEquals("feature/graph", WorkspaceBriefing.branchOfHead("ref: refs/heads/feature/graph\n"))
    // A detached head holds a commit, and a commit is not a branch name.
    assertNull(WorkspaceBriefing.branchOfHead("c5d010e38d0c0ffee\n"))
    assertNull(WorkspaceBriefing.branchOfHead(null))
    assertNull(WorkspaceBriefing.branchOfHead(""))
  }

  @Test
  fun `a stretch of time is read as a person reads it`() {
    assertEquals("11 с", HumanDuration.text(11_300))
    assertEquals("0 с", HumanDuration.text(-5))
    assertEquals("3 мин 5 с", HumanDuration.text(3 * 60_000L + 5_000))
    assertEquals("5 мин 0 с", HumanDuration.text(5 * 60_000L))
    assertEquals("1 ч 4 мин", HumanDuration.text(64 * 60_000L))
  }
}
