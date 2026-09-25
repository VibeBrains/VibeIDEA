// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A request for input in the middle of an MCP call: read as the server wrote it, answered in MCP's words */
class McpInputRequiredTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `a complete result and a result of the older revision are not requests for input`() {
    assertNull(McpInputRequired.of(obj("""{"content":[]}""")))
    assertNull(McpInputRequired.of(obj("""{"resultType":"complete","content":[]}""")))
  }

  @Test
  fun `the requests keep their keys, and an entry without a method is not a request`() {
    val required = McpInputRequired.of(obj(
      """{"resultType":"input_required","requestState":"s","inputRequests":{"a":{"method":"roots/list"},"b":{"params":{}}}}"""))!!
    assertEquals(listOf("a" to "roots/list"), required.requests.map { it.key to it.method })
    val retry = McpInputRequired.retry(obj("""{"name":"t","arguments":{}}"""), required,
                                       mapOf("a" to obj("""{"roots":[]}""")))
    assertEquals("t", retry["name"]!!.jsonPrimitive.content)
    assertEquals("s", retry["requestState"]!!.jsonPrimitive.content)
    assertEquals(obj("""{"a":{"roots":[]}}"""), retry["inputResponses"])
  }

  @Test
  fun `one unanswered request leaves the whole set unanswered`() {
    val required = McpInputRequired.of(obj(
      """{"resultType":"input_required","inputRequests":{"a":{"method":"roots/list"},"b":{"method":"sampling/createMessage"}}}"""))!!
    val answerer = McpInputRequired.Answerer { if (it.method == McpInputRequired.ROOTS) obj("{}") else null }
    assertNull(McpInputRequired.answers(required, answerer))
  }

  @Test
  fun `an elicitation without a mode is a form, and the dialog's outcome is MCP's action`() {
    assertEquals("form", McpInputRequired.elicitationParams(obj("""{"message":"m"}"""))["mode"]!!.jsonPrimitive.content)
    assertEquals("url", McpInputRequired.elicitationParams(obj("""{"mode":"url"}"""))["mode"]!!.jsonPrimitive.content)
    assertEquals(obj("""{"action":"accept","content":{"x":1}}"""),
                 McpInputRequired.elicitationResult(obj("""{"outcome":"accept","content":{"x":1}}""")))
  }

  @Test
  fun `the roots are the folders as file uris`() {
    val folder = java.nio.file.Path.of("/tmp/proj")
    val root = McpInputRequired.rootsResult(listOf(folder))["roots"]!!.jsonArray.single().jsonObject
    assertEquals(folder.toUri().toString(), root["uri"]!!.jsonPrimitive.content)
    assertEquals("proj", root["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a result without requests is retried with the state only`() {
    val shed = McpInputRequired.of(obj("""{"resultType":"input_required","requestState":"s9"}"""))!!
    val retry = McpInputRequired.retry(obj("""{"name":"t","arguments":{}}"""), shed, McpInputRequired.answers(shed, McpInputRequired.Answerer.NONE)!!)
    assertEquals(obj("""{"name":"t","arguments":{},"requestState":"s9"}"""), retry)
  }
}
