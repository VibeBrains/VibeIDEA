// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.mcp.McpServersFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the form of a new server accepts, and what it says about the rest */
class McpServerFormTest {
  private fun input(name: String = "github", command: String = "npx", args: String = "", env: String = "") =
    McpServerForm.Input(name, command, args, env)

  private fun valid(input: McpServerForm.Input, taken: Set<String> = emptySet()): McpServersFile.Entry =
    (McpServerForm.validate(input, taken) as McpServerForm.Verdict.Valid).entry

  private fun invalid(input: McpServerForm.Input, taken: Set<String> = emptySet()): McpServerForm.Verdict.Invalid =
    McpServerForm.validate(input, taken) as McpServerForm.Verdict.Invalid

  // ----- arguments -----

  @Test
  fun `arguments split on spaces and quotes keep a value whole`() {
    assertEquals(listOf("-y", "@modelcontextprotocol/server-github"), McpServerForm.splitArgs("-y @modelcontextprotocol/server-github"))
    assertEquals(listOf("--name", "a b", "c d", "e"), McpServerForm.splitArgs("--name \"a b\" 'c d'   e"))
    assertEquals(listOf("--path=C:\\Program Files\\x"), McpServerForm.splitArgs("--path=\"C:\\Program Files\\x\""))
    assertEquals(emptyList(), McpServerForm.splitArgs("   "))
  }

  @Test
  fun `an empty pair of quotes is an empty argument and a quote of the other kind is just a character`() {
    assertEquals(listOf("a", "", "b"), McpServerForm.splitArgs("a \"\" b"))
    assertEquals(listOf("say 'hi'"), McpServerForm.splitArgs("\"say 'hi'\""))
  }

  @Test
  fun `a quote that never closes is not guessed at`() {
    assertNull(McpServerForm.splitArgs("--name \"a b"))
    assertEquals(McpServerForm.Problem.UNCLOSED_QUOTE, invalid(input(args = "'oops")).problem)
  }

  // ----- environment -----

  @Test
  fun `environment lines are names and values, blank lines are skipped`() {
    val env = McpServerForm.parseEnv("GITHUB_TOKEN=ghp_x\n\n  DEBUG = 1  \r\nURL=https://x?a=b\nEMPTY=\n") as McpServerForm.Env.Ok
    assertEquals(mapOf("GITHUB_TOKEN" to "ghp_x", "DEBUG" to "1", "URL" to "https://x?a=b", "EMPTY" to ""), env.values)
    assertEquals(listOf("GITHUB_TOKEN", "DEBUG", "URL", "EMPTY"), env.values.keys.toList())
  }

  @Test
  fun `a value is kept as it is typed, quotes and references included`() {
    val env = McpServerForm.parseEnv("A=\"quoted\"\nB=\${secret:KEY}") as McpServerForm.Env.Ok
    assertEquals("\"quoted\"", env.values["A"])
    assertEquals("\${secret:KEY}", env.values["B"])
  }

  @Test
  fun `a line that is not a variable is named by its number`() {
    assertEquals(McpServerForm.Env.Bad(2), McpServerForm.parseEnv("A=1\nnot a variable\nB=2"))
    assertEquals(McpServerForm.Env.Bad(1), McpServerForm.parseEnv("=value"))
    assertEquals(McpServerForm.Env.Bad(1), McpServerForm.parseEnv("1BAD=x"))
    assertEquals(McpServerForm.Env.Bad(3), McpServerForm.parseEnv("A=1\n\nmy-var=2"))
    val verdict = invalid(input(env = "A=1\noops"))
    assertEquals(McpServerForm.Problem.BAD_ENV_LINE, verdict.problem)
    assertEquals("2", verdict.detail)
    assertTrue(McpServerForm.describe(verdict).contains("в строке 2"))
  }

  // ----- the whole form -----

  @Test
  fun `a filled form becomes the entry that will be written`() {
    val entry = valid(input(name = "  github ", command = " npx ", args = "-y pkg", env = "K=v"))
    assertEquals(McpServersFile.Entry("github", "npx", listOf("-y", "pkg"), mapOf("K" to "v")), entry)
  }

  @Test
  fun `name and command are required, and a name that is taken is refused by name`() {
    assertEquals(McpServerForm.Problem.NO_NAME, invalid(input(name = "  ")).problem)
    assertEquals(McpServerForm.Problem.NO_COMMAND, invalid(input(command = " ")).problem)
    val taken = invalid(input(name = "github"), taken = setOf("github"))
    assertEquals(McpServerForm.Problem.NAME_TAKEN, taken.problem)
    assertTrue(McpServerForm.describe(taken).contains("«github»"))
    // Names are compared as the file compares them: exactly
    valid(input(name = "GitHub"), taken = setOf("github"))
  }

  @Test
  fun `the first problem found is the one said`() {
    assertEquals(McpServerForm.Problem.NO_NAME, invalid(input(name = "", command = "", args = "'")).problem)
    assertEquals(McpServerForm.Problem.NO_COMMAND, invalid(input(command = "", args = "'")).problem)
  }
}
