// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Models that answer on the Responses wire only: the whole request is refused elsewhere, not only its tools. */
class ResponsesOnlyTest {
  @Test
  fun `the pro models of OpenAI are Responses only, with or without a router prefix`() {
    for (id in listOf("o1-pro", "o3-pro", "o3-pro-2025-06-10", "gpt-5-pro", "openai/o3-pro")) {
      assertTrue(ModelQuirks.has(id, ModelQuirks.Quirk.RESPONSES_ONLY), id)
    }
  }

  @Test
  fun `their siblings are not`() {
    for (id in listOf("o1", "o3", "o3-mini", "gpt-5", "gpt-5-mini", "o1-preview")) {
      assertFalse(ModelQuirks.has(id, ModelQuirks.Quirk.RESPONSES_ONLY), id)
    }
  }
}
