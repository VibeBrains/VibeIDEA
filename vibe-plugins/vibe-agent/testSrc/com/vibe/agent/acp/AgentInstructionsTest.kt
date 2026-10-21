// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.context.ProjectRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentInstructionsTest {
  private val words = AgentInstructions.Words(header = "ШАПКА", always = "ВСЕГДА:", onDemand = "ПО СЛУЧАЮ:", nested = "ВЛОЖЕННЫЕ")

  private fun agent(command: String, args: List<String> = emptyList(), reads: Boolean? = null) =
    AgentServerConfig(name = "a", command = command, args = args, env = emptyMap(), readsInstructions = reads)

  private fun rule(name: String, always: Boolean, source: String = ".vibe/rules/$name.mdc", dir: String = "",
                   globs: List<String> = emptyList(), triggers: List<String> = emptyList()) =
    ProjectRules.Rule(name, dir, description = "о $name", globs = globs, alwaysApply = always, body = "ТЕЛО $name",
                      source = source, triggers = triggers)

  @Test
  fun `the Claude adapter reads its instructions itself by default, other agents do not, and the entry decides over both`() {
    assertTrue(AgentInstructions.readsInstructions(agent("npx", listOf("@agentclientprotocol/claude-agent-acp@0.93.0"))))
    assertFalse(AgentInstructions.readsInstructions(agent("gemini", listOf("--experimental-acp"))))
    assertFalse(AgentInstructions.readsInstructions(agent("npx", listOf("@agentclientprotocol/claude-agent-acp"), reads = false)),
                "запись, сказавшая «нет», получает правила в каждом сообщении, как раньше")
    assertTrue(AgentInstructions.readsInstructions(agent("codex-acp", reads = true)))
  }

  @Test
  fun `always-on rules go in full, the rest as an index, and AGENTS md and package rules not at all`() {
    val block = assertNotNull(AgentInstructions.block(
      listOf(
        rule("dev-engine", always = true),
        rule("review-php", always = false, globs = listOf("**/*.php")),
        rule("autopilot", always = false, triggers = listOf("ночной прогон")),
        rule(ProjectRules.AGENTS_FILE, always = true, source = ProjectRules.AGENTS_FILE),
        rule("web", always = true, dir = "packages/web"),
      ),
      pointers = emptyList(), design = null, words = words))

    assertTrue(block.text.startsWith("ШАПКА"), block.text)
    assertTrue("ТЕЛО dev-engine" in block.text)
    assertFalse("ТЕЛО review-php" in block.text, "правило по случаю идёт путём, а не телом")
    assertTrue("- @review-php — о review-php [**/*.php] → .vibe/rules/review-php.mdc" in block.text, block.text)
    assertTrue("- @autopilot — о autopilot «ночной прогон» → .vibe/rules/autopilot.mdc" in block.text, block.text)
    assertFalse("ТЕЛО AGENTS.md" in block.text, "AGENTS.md агент читает сам")
    assertFalse("ТЕЛО web" in block.text, "правило пакета не корневое")
    assertTrue("ВЛОЖЕННЫЕ" in block.text)
    assertEquals(listOf("dev-engine", "review-php", "autopilot"), block.rules)
  }

  @Test
  fun `a project with nothing the agent cannot read itself gives no block, and pointers alone make one`() {
    assertNull(AgentInstructions.block(
      listOf(rule(ProjectRules.AGENTS_FILE, always = true, source = ProjectRules.AGENTS_FILE)), emptyList(), null, words))

    val notes = assertNotNull(AgentInstructions.block(emptyList(), listOf("знания: docs/knowledge/README.md"), "ДИЗАЙН", words))
    assertTrue("- знания: docs/knowledge/README.md" in notes.text, notes.text)
    assertTrue(notes.text.endsWith("ДИЗАЙН"))
    assertFalse("ВЛОЖЕННЫЕ" in notes.text, "без правил подсказка о правилах пакетов не нужна")
    assertTrue(notes.rules.isEmpty())
  }

  @Test
  fun `the context goes once per session and again only when it changed`() {
    assertEquals("A", AgentInstructions.due("A", delivered = null), "первое сообщение сессии без системного промпта")
    assertNull(AgentInstructions.due("A", delivered = "A"), "уже в системном промпте или в прошлом сообщении")
    assertEquals("B", AgentInstructions.due("B", delivered = "A"), "правила поправили посреди сессии")
    assertNull(AgentInstructions.due(null, delivered = "A"))
  }

  @Test
  fun `the system prompt is appended as one text block`() {
    val prompt = AgentInstructions.systemPrompt("текст")
    assertEquals("append", prompt["mode"]?.jsonPrimitive?.content)
    val block = (prompt["content"] as JsonArray).single().jsonObject
    assertEquals("text", block["type"]?.jsonPrimitive?.content)
    assertEquals("текст", block["text"]?.jsonPrimitive?.content)
  }
}
