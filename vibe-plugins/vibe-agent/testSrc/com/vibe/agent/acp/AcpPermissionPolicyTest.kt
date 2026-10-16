// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.acp.AcpPermissionPolicy.Answer
import com.vibe.agent.mcp.McpProtocol.Risk
import com.vibe.agent.mcp.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who answers an ACP agent's permission request.
 *
 * The policy has no IDE-mode parameter, so a request is put to the person under every mode by construction;
 * the only IDE-side refusal is the workspace trust.
 */
class AcpPermissionPolicyTest {
  private val kinds = listOf("read", "search", "fetch", "think", "switch_mode", "edit", "delete", "move", "execute", "other", null)

  @Test
  fun `trusted project asks the person for every kind`() {
    kinds.forEach { assertEquals(Answer.ASK_USER, AcpPermissionPolicy.answer(it, projectTrusted = true), it.toString()) }
  }

  @Test
  fun `untrusted project refuses writes and commands but not reading`() {
    listOf("edit", "delete", "move", "execute", "other", null).forEach {
      assertEquals(Answer.REFUSE_UNTRUSTED, AcpPermissionPolicy.answer(it, projectTrusted = false), it.toString())
    }
    listOf("read", "search", "fetch", "think", "switch_mode").forEach {
      assertEquals(Answer.ASK_USER, AcpPermissionPolicy.answer(it, projectTrusted = false), it)
    }
  }

  @Test
  fun `unknown kind is the worst case`() {
    assertEquals(Risk.EXECUTE, AcpPermissionPolicy.riskOf("something-new"))
  }

  @Test
  fun `ide permission pill belongs to the direct chat only`() {
    assertTrue(PermissionMode.pillShownFor(agentTarget = false))
    assertFalse(PermissionMode.pillShownFor(agentTarget = true))
  }

  @Test
  fun `direct chat decisions are unchanged`() {
    assertEquals(PermissionMode.Decision.ALLOW, PermissionMode.AUTO.decide(Risk.EXECUTE))
    assertEquals(PermissionMode.Decision.DENY, PermissionMode.PLAN.decide(Risk.WRITE))
  }
}
