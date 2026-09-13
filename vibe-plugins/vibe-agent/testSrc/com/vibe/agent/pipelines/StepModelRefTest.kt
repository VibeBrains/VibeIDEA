// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals

class StepModelRefTest {
  @Test
  fun `the canonical one-string form is split at the first slash`() {
    assertEquals("anthropic" to "claude-opus-5", StepModelRef.resolve(null, "anthropic/claude-opus-5"))
    // OpenRouter ids carry their own slash after the provider.
    assertEquals("openrouter" to "moonshotai/kimi-k3", StepModelRef.resolve(null, "openrouter/moonshotai/kimi-k3"))
  }

  @Test
  fun `the older pair stays a synonym`() {
    assertEquals("minimax" to "MiniMax-M3", StepModelRef.resolve("minimax", "MiniMax-M3"))
  }

  @Test
  fun `a bare model without provider stays a half address for the loader to refuse`() {
    assertEquals(null to "MiniMax-M3", StepModelRef.resolve(null, "MiniMax-M3"))
  }

  @Test
  fun `no model means the step has none`() {
    assertEquals(null to null, StepModelRef.resolve(null, null))
    assertEquals(null to null, StepModelRef.resolve(" ", ""))
  }
}
