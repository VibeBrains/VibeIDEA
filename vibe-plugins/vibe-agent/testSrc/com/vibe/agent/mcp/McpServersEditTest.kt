// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Text edits of `.vibe/mcp.json`
 *
 * The file belongs to the person and to VibeIDE as much as to us, so what an edit must NOT touch matters more than
 * what it changes: neighbours, key order, unknown fields, indent and line breaks stay byte for byte.
 */
class McpServersEditTest {
  private fun lines(vararg parts: String) = parts.joinToString("\n")

  private val spread = lines(
    "{",
    "  \"mcpServers\": {",
    "    \"github\": {",
    "      \"command\": \"npx\",",
    "      \"args\": [\"-y\", \"@modelcontextprotocol/server-github\"]",
    "    },",
    "    \"черновик\": {",
    "      \"command\": \"node\",",
    "      \"cwd\": \"tools\",",
    "      \"disabled\": true",
    "    },",
    "    \"remote\": { \"url\": \"https://mcp.example.com/sse\", \"type\": \"sse\" }",
    "  }",
    "}",
    "",
  )

  private fun edited(outcome: McpServersEdit.Outcome): String = (outcome as McpServersEdit.Outcome.Edited).text

  private fun refused(outcome: McpServersEdit.Outcome): McpServersEdit.Reason = (outcome as McpServersEdit.Outcome.Refused).reason

  private fun entry(name: String, command: String = "npx", args: List<String> = emptyList(), env: Map<String, String> = emptyMap(),
                    disabled: Boolean = false) = McpServersFile.Entry(name, command, args, env, disabled)

  // ----- switching a server off and on -----

  @Test
  fun `switching a server off adds the field under the last one, in the layout of the entry`() {
    val text = edited(McpServersEdit.setDisabled(spread, "github", true))
    assertTrue(text.contains(lines(
      "    \"github\": {",
      "      \"command\": \"npx\",",
      "      \"args\": [\"-y\", \"@modelcontextprotocol/server-github\"],",
      "      \"disabled\": true",
      "    },",
    )), text)
    assertTrue(McpServersFile.parse(text).servers.first { it.name == "github" }.disabled)
  }

  @Test
  fun `switching on and off again gives the file back as it was`() {
    val off = edited(McpServersEdit.setDisabled(spread, "github", true))
    assertEquals(spread, edited(McpServersEdit.setDisabled(off, "github", false)))
  }

  @Test
  fun `switching a server on removes the field wherever it stands and touches nothing else`() {
    val text = edited(McpServersEdit.setDisabled(spread, "черновик", false))
    assertEquals(spread.replace(",\n      \"disabled\": true", ""), text)
    assertFalse(McpServersFile.parse(text).servers.first { it.name == "черновик" }.disabled)
  }

  @Test
  fun `a field written as the word true or as false is replaced in place`() {
    val asText = spread.replace("\"disabled\": true", "\"disabled\": \"true\"")
    assertTrue(McpServersFile.parse(asText).servers.first { it.name == "черновик" }.disabled)
    assertEquals(spread, edited(McpServersEdit.setDisabled(asText, "черновик", true)))
    val off = spread.replace("\"disabled\": true", "\"disabled\": false")
    assertEquals(spread, edited(McpServersEdit.setDisabled(off, "черновик", true)))
  }

  @Test
  fun `switching a server that is already on changes no character`() {
    assertEquals(spread, edited(McpServersEdit.setDisabled(spread, "github", false)))
  }

  @Test
  fun `an entry written on one line gets the field on that line`() {
    val text = edited(McpServersEdit.setDisabled(spread, "remote", true))
    assertTrue(text.contains("    \"remote\": { \"url\": \"https://mcp.example.com/sse\", \"type\": \"sse\", \"disabled\": true }"), text)
  }

  @Test
  fun `a server that is not in the file is refused by name`() {
    assertEquals(McpServersEdit.Reason.NO_SUCH_SERVER, refused(McpServersEdit.setDisabled(spread, "нет", true)))
    assertEquals(McpServersEdit.Reason.NO_SUCH_SERVER, refused(McpServersEdit.remove(spread, "нет")))
  }

  // ----- removing -----

  @Test
  fun `the first, a middle and the last entry leave the others exactly as they were`() {
    val first = edited(McpServersEdit.remove(spread, "github"))
    assertFalse(first.contains("github"))
    assertEquals(listOf("черновик"), McpServersFile.parse(first).servers.map { it.name })
    assertTrue(first.startsWith(lines("{", "  \"mcpServers\": {", "    \"черновик\": {")), first)

    val middle = edited(McpServersEdit.remove(spread, "черновик"))
    assertEquals(listOf("github"), McpServersFile.parse(middle).servers.map { it.name })
    assertTrue(middle.contains(lines("    },", "    \"remote\": {")), middle)

    val last = edited(McpServersEdit.remove(spread, "remote"))
    assertEquals(listOf("github", "черновик"), McpServersFile.parse(last).servers.map { it.name })
    assertTrue(last.endsWith(lines("    }", "  }", "}", "")), last)
  }

  @Test
  fun `the only entry leaves an empty section, and the file still takes a new one`() {
    val one = lines("{", "  \"mcpServers\": {", "    \"a\": { \"command\": \"x\" }", "  }", "}", "")
    val empty = edited(McpServersEdit.remove(one, "a"))
    assertEquals(lines("{", "  \"mcpServers\": {}", "}", ""), empty)
    assertEquals(listOf("b"), McpServersFile.parse(edited(McpServersEdit.add(empty, entry("b", "y")))).servers.map { it.name })
  }

  @Test
  fun `removing from a file kept on one line keeps it on one line`() {
    val compact = "{\"mcpServers\":{\"a\":{\"command\":\"x\"},\"b\":{\"command\":\"y\"},\"c\":{\"command\":\"z\"}}}"
    assertEquals("{\"mcpServers\":{\"a\":{\"command\":\"x\"},\"c\":{\"command\":\"z\"}}}", edited(McpServersEdit.remove(compact, "b")))
    assertEquals("{\"mcpServers\":{\"b\":{\"command\":\"y\"},\"c\":{\"command\":\"z\"}}}", edited(McpServersEdit.remove(compact, "a")))
    assertEquals("{\"mcpServers\":{\"a\":{\"command\":\"x\"},\"b\":{\"command\":\"y\"}}}", edited(McpServersEdit.remove(compact, "c")))
  }

  @Test
  fun `a string with braces and commas in it does not confuse the scan`() {
    val tricky = lines(
      "{",
      "  \"mcpServers\": {",
      "    \"a\": { \"command\": \"x\", \"args\": [\"}\", \",\", \"\\\"\", \"{\"] },",
      "    \"b\": { \"command\": \"y\" }",
      "  }",
      "}",
    )
    val text = edited(McpServersEdit.remove(tricky, "a"))
    assertEquals(listOf("b"), McpServersFile.parse(text).servers.map { it.name })
    assertEquals(listOf("}", ",", "\"", "{"), McpServersFile.parse(tricky).servers.first().args)
  }

  // ----- adding -----

  @Test
  fun `no file yet gives the layout of the spec`() {
    val text = edited(McpServersEdit.add(null, entry("github", "npx", listOf("-y", "@modelcontextprotocol/server-github"),
                                                     mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "ghp_x"))))
    assertEquals(lines(
      "{",
      "  \"mcpServers\": {",
      "    \"github\": {",
      "      \"command\": \"npx\",",
      "      \"args\": [\"-y\", \"@modelcontextprotocol/server-github\"],",
      "      \"env\": { \"GITHUB_PERSONAL_ACCESS_TOKEN\": \"ghp_x\" }",
      "    }",
      "  }",
      "}",
      "",
    ), text)
  }

  @Test
  fun `a blank file is a file that is not there`() {
    assertEquals(edited(McpServersEdit.add(null, entry("a", "x"))), edited(McpServersEdit.add("  \n", entry("a", "x"))))
  }

  @Test
  fun `a new entry goes last and takes the indent of its neighbours`() {
    val text = edited(McpServersEdit.add(spread, entry("jira", "uvx", listOf("mcp-jira"))))
    assertTrue(text.contains(lines(
      "    \"remote\": { \"url\": \"https://mcp.example.com/sse\", \"type\": \"sse\" },",
      "    \"jira\": {",
      "      \"command\": \"uvx\",",
      "      \"args\": [\"mcp-jira\"]",
      "    }",
      "  }",
      "}",
    )), text)
    // Everything above the new entry is the old file, character for character
    assertTrue(text.startsWith(spread.substringBefore("    \"remote\"")))
    assertEquals(listOf("github", "черновик", "jira"), McpServersFile.parse(text).servers.map { it.name })
  }

  @Test
  fun `four spaces and tabs are kept`() {
    val four = "{\n    \"mcpServers\": {\n        \"a\": {\n            \"command\": \"x\"\n        }\n    }\n}\n"
    val fourText = edited(McpServersEdit.add(four, entry("b", "y")))
    assertTrue(fourText.contains("        },\n        \"b\": {\n            \"command\": \"y\"\n        }\n    }"), fourText)

    val tabs = "{\n\t\"mcpServers\": {\n\t\t\"a\": {\n\t\t\t\"command\": \"x\"\n\t\t}\n\t}\n}\n"
    val tabText = edited(McpServersEdit.add(tabs, entry("b", "y")))
    assertTrue(tabText.contains("\t\t},\n\t\t\"b\": {\n\t\t\t\"command\": \"y\"\n\t\t}\n\t}"), tabText)
  }

  @Test
  fun `a file kept on one line gets a new entry on that line`() {
    val compact = "{\"mcpServers\":{\"a\":{\"command\":\"x\"}}}"
    val text = edited(McpServersEdit.add(compact, entry("b", "y", listOf("1"))))
    assertFalse(text.contains('\n'))
    assertEquals(listOf("a", "b"), McpServersFile.parse(text).servers.map { it.name })
    assertEquals(listOf("1"), McpServersFile.parse(text).servers.last().args)
    val empty = edited(McpServersEdit.add("{\"mcpServers\":{}}", entry("b", "y")))
    assertEquals(listOf("b"), McpServersFile.parse(empty).servers.map { it.name })
  }

  @Test
  fun `windows line breaks stay windows line breaks`() {
    val crlf = spread.replace("\n", "\r\n")
    val text = edited(McpServersEdit.add(crlf, entry("jira", "uvx", listOf("x"))))
    assertFalse(text.replace("\r\n", "").contains('\n'), "a bare line feed came in")
    assertEquals("jira", McpServersFile.parse(text).servers.last().name)
  }

  @Test
  fun `a file without the section gets the section and keeps its other keys`() {
    val other = lines("{", "  \"note\": \"mine\"", "}", "")
    val text = edited(McpServersEdit.add(other, entry("a", "x")))
    assertTrue(text.startsWith(lines("{", "  \"note\": \"mine\",", "  \"mcpServers\": {")), text)
    assertEquals(listOf("a"), McpServersFile.parse(text).servers.map { it.name })
    assertEquals(listOf("a"), McpServersFile.parse(edited(McpServersEdit.add("{}", entry("a", "x")))).servers.map { it.name })
  }

  @Test
  fun `what the form wrote reads back the way it was typed`() {
    val typed = entry("мой сервер", "C:\\tools\\srv.exe", listOf("--name", "a \"b\" c", "back\\slash", "юникод"),
                      mapOf("K" to "v=w", "EMPTY" to "", "Q" to "say \"hi\""))
    val read = McpServersFile.parse(edited(McpServersEdit.add(null, typed))).servers.single()
    assertEquals(typed, read)
  }

  @Test
  fun `a name already in the file is refused, not written twice`() {
    assertEquals(McpServersEdit.Reason.NAME_TAKEN, refused(McpServersEdit.add(spread, entry("github", "x"))))
  }

  @Test
  fun `an entry already marked off keeps the mark`() {
    val read = McpServersFile.parse(edited(McpServersEdit.add(null, entry("a", "x", disabled = true)))).servers.single()
    assertTrue(read.disabled)
  }

  // ----- a file that cannot be edited safely -----

  @Test
  fun `text that is not a JSON object is never edited`() {
    for (broken in listOf("это не json", "[1, 2]", "{\"mcpServers\": [}", "{\"mcpServers\": 5}", "{\"mcpServers\": {\"a\": 1,}}")) {
      assertEquals(McpServersEdit.Reason.UNREADABLE, refused(McpServersEdit.add(broken, entry("a", "x"))), broken)
      assertEquals(McpServersEdit.Reason.UNREADABLE, refused(McpServersEdit.remove(broken, "a")), broken)
      assertEquals(McpServersEdit.Reason.UNREADABLE, refused(McpServersEdit.setDisabled(broken, "a", true)), broken)
    }
  }

  @Test
  fun `the caret goes to the key of the entry`() {
    val at = McpServersEdit.offsetOf(spread, "черновик")!!
    assertTrue(spread.substring(at).startsWith("\"черновик\": {"))
    assertNull(McpServersEdit.offsetOf(spread, "нет"))
    assertNull(McpServersEdit.offsetOf("это не json", "a"))
  }
}
