// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same pair of tool definitions must be "the same" or "changed" in VibeIDE and here
 * The shared vectors (`testVectors/mcpToolPins.json`, VibeBrains) hold the pairs: a reorder of approved words is not a swap,
 * a new word is
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class ToolFingerprintVectorsTest {
  private val cases by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("$VECTORS is not on the classpath: the submodule pointer was not raised")
    Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
  }

  private fun spec(tool: JsonObject): ToolSpec {
    assertEquals(setOf("name", "description", "inputSchema"), tool.keys, "unknown fields of a tool")
    return ToolSpec(tool["name"]!!.jsonPrimitive.content, tool["description"]!!.jsonPrimitive.content, tool["inputSchema"]!!.jsonObject)
  }

  @Test
  fun `the vectors are not empty`() {
    assertTrue(cases.size >= 10, "the shared vectors hold ${cases.size} cases: the file is damaged or the wrong one")
  }

  @Test
  fun `every vector pair gives the same answer as in VibeIDE`() {
    for (case in cases) {
      assertEquals(setOf("name", "why", "a", "b", "same"), case.keys, "unknown fields of a case")
      val a = spec(case["a"]!!.jsonObject)
      val b = spec(case["b"]!!.jsonObject)
      val drift = ToolFingerprint.compare(ToolFingerprint.map(listOf(a)), ToolFingerprint.map(listOf(b)))
      val name = case["name"]!!.jsonPrimitive.content
      assertEquals(case["same"]!!.jsonPrimitive.boolean, drift.isEmpty, "$name: ${case["why"]!!.jsonPrimitive.content}")
    }
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/mcpToolPins.json"
  }
}
