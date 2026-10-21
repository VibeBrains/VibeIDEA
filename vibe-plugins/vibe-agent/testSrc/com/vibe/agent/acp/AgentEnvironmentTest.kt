// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentEnvironmentTest {
  @Test
  fun `an empty value removes the variable instead of leaving it set and empty`() {
    val target = mutableMapOf("ANTHROPIC_API_KEY" to "sk-from-the-machine", "PATH" to "/bin")
    AgentEnvironment.apply(target, mapOf("ANTHROPIC_API_KEY" to "", "EXTRA" to "1"), { null })
    assertFalse("ANTHROPIC_API_KEY" in target, "пустое значение снимает переменную — иначе она остаётся ЗАДАННОЙ")
    assertEquals("1", target["EXTRA"])
    assertEquals("/bin", target["PATH"], "чего запись не касается, то остаётся от IDE")
  }

  @Test
  fun `a resolved secret is substituted, also inside a longer string`() {
    val target = mutableMapOf<String, String>()
    AgentEnvironment.apply(target, mapOf("TOKEN" to "\${secret:TOKEN}", "AUTH" to "Bearer \${secret:TOKEN}!"), { if (it == "TOKEN") "tok" else null })
    assertEquals("tok", target["TOKEN"])
    assertEquals("Bearer tok!", target["AUTH"])
  }

  @Test
  fun `an unresolved secret drops the variable and is reported by name, never as the reference text`() {
    val target = mutableMapOf("OLD" to "x")
    val reported = mutableListOf<Pair<String, List<String>>>()
    AgentEnvironment.apply(
      target,
      mapOf("OLD" to "\${secret:MISSING}", "EMPTY" to "\${secret:BLANK}", "PART" to "a-\${secret:TOKEN}-\${secret:MISSING}"),
      { mapOf("TOKEN" to "tok", "BLANK" to "")[it] },
    ) { variable, secrets -> reported += variable to secrets }
    assertTrue(target.isEmpty(), "ни ссылка, ни полузамена до агента не доходят: $target")
    assertEquals(listOf("OLD" to listOf("MISSING"), "EMPTY" to listOf("BLANK"), "PART" to listOf("MISSING")), reported)
  }

  @Test
  fun `marks of the Claude Code session the IDE was started from do not reach the agent, unless the entry sets them`() {
    val target = mutableMapOf("CLAUDECODE" to "1", "CLAUDE_CODE_SIMPLE" to "1", "CLAUDE_CODE_ENTRYPOINT" to "cli",
                              "CLAUDE_CONFIG_DIR" to "/home/me/.claude", "CLAUDE_CODE_OAUTH_TOKEN" to "tok")
    AgentEnvironment.dropForeignSession(target)
    AgentEnvironment.apply(target, mapOf("CLAUDE_CODE_ENTRYPOINT" to "vibe"), { null })
    assertFalse("CLAUDECODE" in target, "агент не должен считать себя вложенной сессией")
    assertFalse("CLAUDE_CODE_SIMPLE" in target, "голый режим не читает CLAUDE.md и правила пользователя")
    assertEquals("vibe", target["CLAUDE_CODE_ENTRYPOINT"], "заданное записью — последнее слово человека")
    assertEquals("/home/me/.claude", target["CLAUDE_CONFIG_DIR"], "настройки пользователя остаются")
    assertEquals("tok", target["CLAUDE_CODE_OAUTH_TOKEN"], "вход подпиской — не метка сессии")
  }
}
