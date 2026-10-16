// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.checkpoints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The contract counts commands by the direct chat's tool names, so an agent's command tool must be recorded under one */
class AgentShellToolsTest {
  @Test
  fun `a call of the execute kind is a command whatever the agent calls it`() {
    assertEquals("run_command", AgentShellTools.recordedName("execute", "run_shell_command"))
    assertEquals("run_command", AgentShellTools.recordedName("execute", "shell"))
    assertEquals("run_command", AgentShellTools.recordedName("execute", null))
  }

  @Test
  fun `a tool the audit knows as a command is one when the kind did not arrive`() {
    assertEquals("run_command", AgentShellTools.recordedName(null, "Bash"))
    assertEquals("run_command", AgentShellTools.recordedName("other", "Bash"))
  }

  @Test
  fun `the tools that only follow a command are not commands of their own`() {
    assertEquals("BashOutput", AgentShellTools.recordedName("execute", "BashOutput"))
    assertEquals("KillShell", AgentShellTools.recordedName(null, "KillShell"))
  }

  @Test
  fun `any other tool keeps its name`() {
    assertEquals("Edit", AgentShellTools.recordedName("edit", "Edit"))
    assertEquals("read_file", AgentShellTools.recordedName("read", "read_file"))
    assertNull(AgentShellTools.recordedName("read", null))
  }

  @Test
  fun `a recorded command counts as a command in the warning`() {
    val names = listOf(AgentShellTools.recordedName("execute", "Bash"), AgentShellTools.recordedName("edit", "Edit"))
    assertEquals(RewindCoverage.Uncovered(1), CheckpointContract.coverage(false, null, names.filterNotNull()))
  }
}
