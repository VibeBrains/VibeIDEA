// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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

  @Test
  fun `marks of the Claude Code session the IDE was started from do not reach the agent, unless the entry sets them`() {
    val target = mutableMapOf("CLAUDECODE" to "1", "CLAUDE_CODE_SIMPLE" to "1", "CLAUDE_CODE_ENTRYPOINT" to "cli",
                              "CLAUDE_CONFIG_DIR" to "/home/me/.claude", "CLAUDE_CODE_OAUTH_TOKEN" to "tok")
    AgentEnvironment.dropForeignSession(target)
    AgentEnvironment.apply(target, mapOf("CLAUDE_CODE_ENTRYPOINT" to "vibe")) { it }
    assertFalse("CLAUDECODE" in target, "агент не должен считать себя вложенной сессией")
    assertFalse("CLAUDE_CODE_SIMPLE" in target, "голый режим не читает CLAUDE.md и правила пользователя")
    assertEquals("vibe", target["CLAUDE_CODE_ENTRYPOINT"], "заданное записью — последнее слово человека")
    assertEquals("/home/me/.claude", target["CLAUDE_CONFIG_DIR"], "настройки пользователя остаются")
    assertEquals("tok", target["CLAUDE_CODE_OAUTH_TOKEN"], "вход подпиской — не метка сессии")
  }
}
