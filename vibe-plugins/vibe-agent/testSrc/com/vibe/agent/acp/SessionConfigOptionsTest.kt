// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Session config options as the ACP spec writes them (agentclientprotocol.com/protocol/v1/session-config-options). */
class SessionConfigOptionsTest {
  private fun parse(json: String) = SessionConfigOptions.parse(Json.parseToJsonElement(json).jsonObject)

  @Test
  fun `a boolean option from the spec example is read from currentValue`() {
    val options = parse("""{"configOptions":[{"id":"brave_mode","name":"Brave Mode","description":"Skip confirmation prompts","type":"boolean","currentValue":true}]}""")
    assertEquals(SessionConfigOption("brave_mode", "Brave Mode", "Skip confirmation prompts", null, SessionConfigOption.Toggle(true)), options.single())
  }

  @Test
  fun `a select option from the spec example keeps its values and category`() {
    val option = parse("""{"configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"model-1",
      "options":[{"value":"model-1","name":"Model 1","description":"The fastest model"},{"value":"model-2","name":"Model 2"}]}]}""").single()
    assertEquals("model", option.category)
    val choice = option.kind as SessionConfigOption.Choice
    assertEquals("model-1", choice.current)
    assertEquals("Model 1", choice.currentName)
    assertEquals(listOf("model-1", "model-2"), choice.options.map { it.value })
  }

  @Test
  fun `grouped choices are flattened`() {
    val choice = parse("""{"configOptions":[{"id":"model","name":"Model","type":"select","currentValue":"b",
      "options":[{"group":"g1","name":"Fast","options":[{"value":"a","name":"A"}]},{"group":"g2","name":"Smart","options":[{"value":"b","name":"B"}]}]}]}""")
      .single().kind as SessionConfigOption.Choice
    assertEquals(listOf("a", "b"), choice.options.map { it.value })
    assertEquals("B", choice.currentName)
  }

  @Test
  fun `an agent built against the draft still sends value and is still read`() {
    val option = parse("""{"configOptions":[{"id":"fast","name":"Fast","type":"boolean","value":false}]}""").single()
    assertEquals(SessionConfigOption.Toggle(false), option.kind)
  }

  @Test
  fun `unknown types and malformed options are ignored, the rest stay`() {
    val options = parse("""{"configOptions":[
      {"id":"slider","name":"Slider","type":"number","currentValue":3},
      {"id":"broken","name":"Broken","type":"select","currentValue":"x","options":[]},
      {"id":"wrong","name":"Wrong","type":"boolean","currentValue":"yes"},
      {"id":"ok","name":"Ok","type":"boolean","currentValue":false}]}""")
    assertEquals(listOf("ok"), options.map { it.id })
  }

  @Test
  fun `no configOptions field means no options`() {
    assertTrue(parse("""{"sessionId":"s"}""").isEmpty())
  }
}
