// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edits from the Commands menu touch the characters they have to and leave the person's comments and layout alone */
class ProjectCommandsEditTest {
  private val file = """
    // project commands
    {
      "vibeVersion": "1.0.0",
      "commands": [
        // the build
        { "id": "build", "name": "Сборка", "command": "npm", "args": ["run", "build"], "pinned": false },
        {
          "id": "test",
          "name": "Тесты { и ] в подписи }",
          "command": "npm",
          "env": { "id": "build" }
        },
        { "id": "deploy", "name": "Выкатить", "command": "./deploy.sh" } // the last one
      ]
    }
  """.trimIndent()

  private fun ids(text: String) = ProjectCommands.parse(text).commands.map { it.id }.sorted()

  private fun pinned(text: String) = ProjectCommands.parse(text).commands.filter { it.pinned }.map { it.id }

  @Test
  fun `pinning rewrites the field in place or adds it after the id`() {
    val first = ProjectCommandsEdit.setPinned(file, "build", true)!!
    assertEquals(listOf("build"), pinned(first))
    assertEquals(file.replace("\"pinned\": false", "\"pinned\": true"), first)
    val second = ProjectCommandsEdit.setPinned(first, "test", true)!!
    assertEquals(listOf("build", "test"), pinned(second).sorted())
    assertTrue("\"id\": \"test\", \"pinned\": true," in second)
    assertEquals(listOf("test"), pinned(ProjectCommandsEdit.setPinned(second, "build", false)!!))
  }

  @Test
  fun `an id inside env is a value, not a command`() {
    // The object under `env` carries "id": "build" and must not be taken for the command of that name.
    val edited = ProjectCommandsEdit.setPinned(file, "build", true)!!
    assertTrue("\"env\": { \"id\": \"build\" }" in edited)
  }

  @Test
  fun `removing takes the command, its comma and its line, and nothing else`() {
    for (id in listOf("build", "test", "deploy")) {
      val edited = ProjectCommandsEdit.remove(file, id)!!
      val parsed = ProjectCommands.parse(edited)
      assertEquals(emptyList(), parsed.problems, id)
      assertEquals(listOf("build", "deploy", "test") - id, ids(edited), id)
      assertTrue("// project commands" in edited && "\"vibeVersion\": \"1.0.0\"" in edited, id)
    }
    val last = listOf("build", "test", "deploy").fold(file) { text, id -> ProjectCommandsEdit.remove(text, id)!! }
    assertEquals(emptyList(), ids(last))
    assertEquals(emptyList(), ProjectCommands.parse(last).problems)
  }

  @Test
  fun `the place of a command is where its object opens`() {
    val offset = ProjectCommandsEdit.offsetOf(file, "deploy")!!
    assertTrue(file.substring(offset).startsWith("{ \"id\": \"deploy\""))
  }

  @Test
  fun `a command that is not in the text changes nothing`() {
    assertNull(ProjectCommandsEdit.setPinned(file, "nope", true))
    assertNull(ProjectCommandsEdit.remove(file, "nope"))
    assertNull(ProjectCommandsEdit.offsetOf(file, "nope"))
  }
}
