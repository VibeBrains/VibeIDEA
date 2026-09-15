// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tool calling on the openai, anthropic and gemini wires: offered tools, streamed calls, returned results. */
class ToolCallsTest {
  private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private val schema = obj("""{"${'$'}schema":"x","type":"object","additionalProperties":false,"properties":{"query":{"type":"string"}}}""")
  private val tool = ToolSpec("memory_search", "Search memory", schema)
  private val call = ToolCall("c1", "memory_search", """{"query":"roles"}""")
  private val results = ChatMessage(ToolCalls.ROLE, "", toolResults = listOf(
    ToolResult("c1", "memory_search", "found one"),
    ToolResult("c2", "memory_get", "no such record", isError = true),
  ))

  @Test
  fun `tools are offered in each wire's shape`() {
    val openAi = ToolCalls.openAiTools(listOf(tool)).single().jsonObject
    assertEquals("function", openAi["type"]!!.jsonPrimitive.content)
    assertEquals(schema, openAi["function"]!!.jsonObject["parameters"])
    assertEquals(schema, ToolCalls.anthropicTools(listOf(tool)).single().jsonObject["input_schema"])
    val gemini = ToolCalls.geminiTools(listOf(tool)).single().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject
    val parameters = gemini["parameters"]!!.jsonObject
    assertFalse("\$schema" in parameters, "Gemini rejects \$schema")
    assertFalse("additionalProperties" in parameters)
    assertTrue("properties" in parameters)
  }

  @Test
  fun `openai calls stream in pieces and are assembled by index`() {
    val acc = ToolCallAccumulator()
    acc.openAiChunk(obj("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"memory_search","arguments":""}}]}}]}"""))
    acc.openAiChunk(obj("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":"}}]}}]}"""))
    acc.openAiChunk(obj("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"roles\"}"}}]}}]}"""))
    acc.openAiChunk(obj("""{"choices":[{"delta":{"content":"text only"}}]}"""))
    assertEquals(listOf(call), acc.calls())
  }

  @Test
  fun `a non-streaming openai message carries whole calls`() {
    val acc = ToolCallAccumulator()
    acc.openAiMessage(obj("""{"tool_calls":[{"id":"c1","type":"function","function":{"name":"memory_search","arguments":"{\"query\":\"roles\"}"}}]}"""))
    assertEquals(listOf(call), acc.calls())
  }

  @Test
  fun `anthropic tool_use blocks collect their input_json_delta`() {
    val acc = ToolCallAccumulator()
    acc.anthropicEvent(obj("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""))
    acc.anthropicEvent(obj("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"c1","name":"memory_search","input":{}}}"""))
    acc.anthropicEvent(obj("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}"""))
    acc.anthropicEvent(obj("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"roles\"}"}}"""))
    assertEquals(listOf(call), acc.calls())
  }

  @Test
  fun `gemini function calls arrive whole and get ids`() {
    val acc = ToolCallAccumulator()
    acc.geminiEvent(obj("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"memory_search","args":{"query":"roles"}}}]}}]}"""))
    val got = acc.calls().single()
    assertEquals("memory_search", got.name)
    assertEquals(obj("""{"query":"roles"}"""), got.argumentsObject())
  }

  @Test
  fun `a call without arguments reads as an empty object`() {
    assertEquals(JsonObject(emptyMap()), ToolCall("c", "memory_search", "").argumentsObject())
    assertEquals(JsonObject(emptyMap()), ToolCall("c", "memory_search", "{broken").argumentsObject())
  }

  @Test
  fun `openai results are one tool message each, the assistant keeps its calls`() {
    val wire = ToolCalls.openAiResults(results)
    assertEquals(listOf("c1", "c2"), wire.map { it["tool_call_id"]!!.jsonPrimitive.content })
    assertTrue(wire.all { it["role"]!!.jsonPrimitive.content == "tool" })
    val assistant = LlmMessages.openAi(ChatMessage("assistant", "", toolCalls = listOf(call)))
    assertEquals("memory_search", assistant["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun `anthropic results travel in one user message`() {
    val wire = ToolCalls.anthropicResults(results)
    assertEquals("user", wire["role"]!!.jsonPrimitive.content)
    val blocks = wire["content"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("c1", "c2"), blocks.map { it["tool_use_id"]!!.jsonPrimitive.content })
    assertTrue("is_error" !in blocks[0] && "is_error" in blocks[1])
    val assistant = ToolCalls.anthropicAssistant(ChatMessage("assistant", "Looking", toolCalls = listOf(call)))
    assertEquals(listOf("text", "tool_use"), assistant["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
  }

  @Test
  fun `gemini results are matched by name`() {
    val parts = ToolCalls.geminiResults(results)["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
    assertEquals(listOf("memory_search", "memory_get"), parts.map { it["name"]!!.jsonPrimitive.content })
    assertTrue("error" in parts[1]["response"]!!.jsonObject)
  }

  @Test
  fun `a message without tools keeps its old wire shape`() {
    val plain = ChatMessage("assistant", "hi")
    assertEquals(obj("""{"role":"assistant","content":"hi"}"""), LlmMessages.openAi(plain))
    assertEquals(obj("""{"role":"assistant","content":"hi"}"""), LlmMessages.anthropic(plain))
  }

  @Test
  fun `gemini thought signatures are kept on the call and sent back on its part`() {
    val acc = ToolCallAccumulator()
    acc.geminiEvent(obj("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"memory_search","args":{"query":"roles"}},"thoughtSignature":"c2lnLTE="}]}}]}"""))
    val got = acc.calls().single()
    assertEquals("c2lnLTE=", got.signature)
    val part = ToolCalls.geminiAssistant(ChatMessage("assistant", "", toolCalls = listOf(got)))["parts"]!!.jsonArray.single().jsonObject
    assertEquals("c2lnLTE=", part["thoughtSignature"]!!.jsonPrimitive.content)
    assertTrue("thoughtSignature" !in ToolCalls.geminiAssistant(ChatMessage("assistant", "", toolCalls = listOf(call)))["parts"]!!.jsonArray.single().jsonObject)
  }

  @Test
  fun `a signature survives the thread history`() {
    val signed = ToolRound("", listOf(call.copy(signature = "c2lnLTE=")), listOf(ToolResult("c1", "memory_search", "ok")))
    assertEquals(listOf(signed), ToolRounds.fromJson(ToolRounds.toJson(listOf(signed))))
  }
}
