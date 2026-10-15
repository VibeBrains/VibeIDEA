// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One servers file must give the same servers and the same complaints in VibeIDE and here
 * The shared vectors (`testVectors/mcpServersFile.json`, VibeBrains) hold the parse of one file and the two files laid over each other
 * The layers are also laid out as real files, so the test sees which path is read as which layer
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class McpServersFileVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("$VECTORS is not on the classpath: the submodule pointer was not raised")
    Json.parseToJsonElement(text).jsonObject
  }

  /** What a server that runs here looks like in the vectors: the normal form of the entry */
  private data class Stdio(val command: String, val args: List<String>, val env: Map<String, String>, val disabled: Boolean)

  /** Everything the vectors say about a result: the servers that run, the remote names, the complaints of one file */
  private data class Picture(val stdio: Map<String, Stdio>, val remote: List<String>)

  private fun complaints(list: List<McpServersFile.Complaint>): List<Pair<String, String?>> = list.map { it.problem.name to it.name }

  private fun pictureOf(servers: List<McpServersFile.Entry>, remote: List<String>) =
    Picture(servers.associate { it.name to Stdio(it.command, it.args, it.env, it.disabled) }, remote)

  private fun expectedPicture(expect: JsonObject) = Picture(
    expect["stdio"]!!.jsonObject.mapValues { (_, entry) ->
      val body = entry.jsonObject
      assertEquals(emptySet(), body.keys - STDIO_FIELDS, "unknown fields of a server")
      Stdio(
        body["command"]!!.jsonPrimitive.content,
        body["args"]!!.jsonArray.map { it.jsonPrimitive.content },
        body["env"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        body["disabled"]!!.jsonPrimitive.boolean,
      )
    },
    expect["remote"]!!.jsonArray.map { it.jsonPrimitive.content },
  )

  private fun expectedComplaints(array: JsonArray): List<Pair<String, String?>> = array.map {
    val body = it.jsonObject
    assertEquals(emptySet(), body.keys - PROBLEM_FIELDS, "unknown fields of a complaint")
    body["problem"]!!.jsonPrimitive.content to body["name"]?.jsonPrimitive?.content
  }

  private fun text(lines: JsonArray): String = lines.joinToString("\n") { it.jsonPrimitive.content }

  /** Runs every case and reports all the ones that differ at once, each under its name */
  private fun eachCase(cases: JsonArray, caseFields: Set<String>, minimum: Int, verify: (JsonObject) -> Unit) {
    assertTrue(cases.size >= minimum, "fewer vectors than before: ${cases.size}")
    val failures = cases.mapNotNull { element ->
      val case = element.jsonObject
      runCatching {
        assertEquals(emptySet(), case.keys - caseFields, "unknown fields of a case")
        verify(case)
      }.exceptionOrNull()?.let { "${case["name"]?.jsonPrimitive?.content}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "unknown fields of the vectors file")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int, "a new version of the vectors format has to be read, not guessed")
  }

  @Test
  fun `one file gives the servers and the complaints the vectors say`() {
    eachCase(vectors["parse"]!!.jsonArray, PARSE_FIELDS, MIN_PARSE) { case ->
      val expect = case["expect"]!!.jsonObject
      assertEquals(emptySet(), expect.keys - PARSE_EXPECT_FIELDS, "unknown fields of an outcome")
      val parsed = McpServersFile.parse(text(case["lines"]!!.jsonArray))
      assertEquals(expectedPicture(expect), pictureOf(parsed.servers, parsed.remote))
      assertEquals(expectedComplaints(expect["problems"]!!.jsonArray), complaints(parsed.problems))
    }
  }

  @Test
  fun `two files laid over each other give the servers, the owners and the complaints the vectors say`() {
    eachCase(vectors["layers"]!!.jsonArray, LAYERS_FIELDS, MIN_LAYERS) { case ->
      val expect = case["expect"]!!.jsonObject
      assertEquals(emptySet(), expect.keys - LAYERS_EXPECT_FIELDS, "unknown fields of an outcome")
      val merged = McpServersFile.merge(
        McpServersFile.parse(text(case["user"]!!.jsonArray)),
        McpServersFile.parse(text(case["project"]!!.jsonArray)),
      )
      assertMerged(expect, merged)
    }
  }

  @Test
  fun `the two paths are read as the two layers`(@TempDir dir: Path) {
    eachCase(vectors["layers"]!!.jsonArray, LAYERS_FIELDS, MIN_LAYERS) { case ->
      val home = Files.createTempDirectory(dir, "home")
      val project = Files.createTempDirectory(dir, "project")
      write(McpServersFile.userPath(home.toString()), text(case["user"]!!.jsonArray))
      write(McpServersFile.pathIn(project.toString())!!, text(case["project"]!!.jsonArray))
      assertMerged(case["expect"]!!.jsonObject, McpServersFile.loadLayers(project.toString(), home.toString()))
    }
  }

  private fun write(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
  }

  private fun assertMerged(expect: JsonObject, merged: McpServersFile.Merged) {
    assertEquals(expectedPicture(expect), pictureOf(merged.servers, merged.remote))
    val owners = expect["sourceOfName"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    assertEquals(owners, merged.sourceOfName.mapValues { it.value.name.lowercase() }, "whose entry stands")
    val problems = expect["problems"]!!.jsonObject
    assertEquals(emptySet(), problems.keys - LAYER_NAMES, "unknown layers of complaints")
    assertEquals(expectedComplaints(problems["user"]!!.jsonArray), complaints(merged.problems.getValue(McpServersFile.Layer.USER)))
    assertEquals(expectedComplaints(problems["project"]!!.jsonArray), complaints(merged.problems.getValue(McpServersFile.Layer.PROJECT)))
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/mcpServersFile.json"
    const val MIN_PARSE = 10
    const val MIN_LAYERS = 4
    val FILE_FIELDS = setOf("_comment", "version", "parse", "layers")
    val PARSE_FIELDS = setOf("name", "lines", "expect")
    val PARSE_EXPECT_FIELDS = setOf("stdio", "remote", "problems")
    val LAYERS_FIELDS = setOf("name", "user", "project", "expect")
    val LAYERS_EXPECT_FIELDS = setOf("stdio", "remote", "sourceOfName", "problems")
    val STDIO_FIELDS = setOf("command", "args", "env", "disabled")
    val PROBLEM_FIELDS = setOf("problem", "name")
    val LAYER_NAMES = setOf("user", "project")
  }
}
