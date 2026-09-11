// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentEnvironmentTest {
  @Test
  fun `an empty value removes the variable instead of leaving it set and empty`() {
    val target = mutableMapOf("ANTHROPIC_API_KEY" to "sk-from-the-machine", "PATH" to "/bin")
    AgentEnvironment.apply(target, mapOf("ANTHROPIC_API_KEY" to "", "EXTRA" to "1")) { it }
    assertFalse("ANTHROPIC_API_KEY" in target, "пустое значение снимает переменную — иначе она остаётся ЗАДАННОЙ")
    assertEquals("1", target["EXTRA"])
    assertEquals("/bin", target["PATH"], "чего запись не касается, то остаётся от IDE")
  }

  @Test
  fun `values are resolved before they are set, and a secret resolving to nothing is removed`() {
    val target = mutableMapOf("OLD" to "x")
    AgentEnvironment.apply(target, mapOf("TOKEN" to "\${secret:TOKEN}", "OLD" to "\${secret:MISSING}")) { value ->
      if (value == "\${secret:TOKEN}") "tok" else ""
    }
    assertEquals("tok", target["TOKEN"])
    assertFalse("OLD" in target)
  }
}
