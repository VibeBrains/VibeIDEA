// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The line a pipeline is shown as in the picker: roles in order, a wave side by side, a step's own model as a mark
 */
class PipelineChainTest {
  private fun step(role: String, wave: String? = null, provider: String? = null, model: String? = null) =
    PipelineStep(role = role, task = "t", wave = wave, provider = provider, model = model)

  @Test
  fun `consecutive steps are joined by an arrow`() {
    val steps = listOf(step("planner"), step("backend-dev"), step("code-reviewer"))
    assertEquals("planner → backend-dev → code-reviewer", PipelineChain.text(steps))
  }

  @Test
  fun `the steps of a wave stand side by side and the wave is one link of the chain`() {
    val steps = listOf(step("backend-dev"), step("code-reviewer", "check"), step("security", "check"), step("critic"))
    assertEquals("backend-dev → code-reviewer ∥ security → critic", PipelineChain.text(steps))
  }

  @Test
  fun `a wave label on one step alone changes nothing`() {
    val steps = listOf(step("planner", "lonely"), step("backend-dev"))
    assertEquals("planner → backend-dev", PipelineChain.text(steps))
  }

  @Test
  fun `two waves in a row are told apart`() {
    val steps = listOf(step("backend-dev", "a"), step("frontend-dev", "a"), step("qa", "b"), step("security", "b"))
    assertEquals("backend-dev ∥ frontend-dev → qa ∥ security", PipelineChain.text(steps))
  }

  @Test
  fun `a step on its own model carries the short model name after its role`() {
    val steps = listOf(step("code-reviewer", provider = "minimax", model = "MiniMax-M3"), step("critic", model = "anthropic/claude-fable-5-1"))
    assertEquals("code-reviewer (MiniMax-M3) → critic (claude-fable-5-1)", PipelineChain.text(steps))
  }

  @Test
  fun `the model mark is a token of its own so that it can be painted small`() {
    val tokens = PipelineChain.tokens(listOf(step("critic", model = "m1"), step("qa")))
    assertEquals(
      listOf(
        PipelineChain.Token("critic", PipelineChain.Kind.ROLE),
        PipelineChain.Token(" (m1)", PipelineChain.Kind.MODEL),
        PipelineChain.Token(PipelineChain.AFTER, PipelineChain.Kind.LINK),
        PipelineChain.Token("qa", PipelineChain.Kind.ROLE),
      ),
      tokens,
    )
  }

  @Test
  fun `a model inside a wave is marked on its own step`() {
    val steps = listOf(step("code-reviewer", "w", model = "m1"), step("security", "w"))
    assertEquals("code-reviewer (m1) ∥ security", PipelineChain.text(steps))
  }

  @Test
  fun `one step is just its role`() {
    assertEquals("orchestrator", PipelineChain.text(listOf(step("orchestrator"))))
  }

  @Test
  fun `a step without a model of its own has no model name`() {
    assertNull(PipelineChain.modelName(step("qa")))
    assertNull(PipelineChain.modelName(step("qa", model = "  ")))
  }
}
