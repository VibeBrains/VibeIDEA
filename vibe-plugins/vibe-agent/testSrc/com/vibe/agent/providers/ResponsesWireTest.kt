// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.ChatThread
import com.vibe.agent.history.ChatTranscriptCodec
import com.vibe.agent.history.HistoryStore
import com.vibe.agent.history.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Responses wire: what goes into `input`, what the stream means, and what goes back to the model.
 *
 * Shapes follow the official SDK's types; nothing here is checked against a live key.
 */
class ResponsesWireTest {
  private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private val key = ResponsesReplay.keyOf("openai", "gpt-6-sol")

  private val reasoningItem = obj("""{"type":"reasoning","id":"rs_1","status":"completed","summary":[],
    "content":[{"type":"reasoning_text","text":"raw"}],"encrypted_content":"gAAA"}""")
  private val callItem = obj("""{"type":"function_call","id":"fc_1","call_id":"call_1","name":"memory_search",
    "arguments":"{\"query\":\"roles\"}","status":"completed"}""")
  private val messageItem = obj("""{"type":"message","id":"msg_1","role":"assistant","status":"completed","phase":"commentary",
    "content":[{"type":"output_text","text":"Looking.","annotations":[],"logprobs":[]}]}""")

  private fun accumulated(vararg items: JsonObject): List<JsonObject> =
    ResponsesAccumulator().apply { items.forEach(::item) }.items()

  private fun JsonObject.s(key: String): String? = this[key]?.jsonPrimitive?.content

  @Test
  fun `the system prompt becomes instructions and the conversation input items`() {
    val (instructions, input) = ResponsesWire.input(listOf(
      ChatMessage("system", "rules"),
      ChatMessage("user", "where?", images = listOf(ImagePart("image/png", "AAAA"))),
      ChatMessage("assistant", "here"),
      ChatMessage("user", "and?"),
    ), key)
    assertEquals("rules", instructions)
    assertEquals(3, input.size)
    val content = input[0].jsonObject["content"] as JsonArray
    assertEquals("input_text", content[0].jsonObject.s("type"))
    assertEquals("data:image/png;base64,AAAA", content[1].jsonObject.s("image_url"))
    assertEquals("assistant", input[1].jsonObject.s("role"))
    assertEquals("here", input[1].jsonObject.s("content"))
  }

  @Test
  fun `a round without items goes as calls without ids and their outputs`() {
    val call = ToolCall("call_1", "memory_search", "")
    val (_, input) = ResponsesWire.input(listOf(
      ChatMessage("user", "where?"),
      ChatMessage("assistant", "", toolCalls = listOf(call)),
      ChatMessage(ToolCalls.ROLE, "", toolResults = listOf(ToolResult("call_1", "memory_search", "found"))),
    ), key)
    val sent = input[1].jsonObject
    assertEquals("function_call", sent.s("type"))
    assertEquals("call_1", sent.s("call_id"))
    // No item id: an id ties the call to reasoning that is not being sent, and the vendor refuses that pair.
    assertNull(sent["id"])
    assertEquals("{}", sent.s("arguments"))
    assertEquals("function_call_output", input[2].jsonObject.s("type"))
    assertEquals("found", input[2].jsonObject.s("output"))
  }

  @Test
  fun `the same model gets its own items back, another model the plain round`() {
    val replay = ResponsesReplay(key, accumulated(reasoningItem, messageItem, callItem))
    val round = ChatMessage("assistant", "Looking.", toolCalls = listOf(ToolCall("call_1", "memory_search", "{}")), responses = replay)
    val same = ResponsesWire.input(listOf(round), key).second
    assertEquals(listOf("reasoning", "message", "function_call"), same.map { it.jsonObject.s("type") })
    assertEquals("gAAA", same[0].jsonObject.s("encrypted_content"))
    val other = ResponsesWire.input(listOf(round), ResponsesReplay.keyOf("openai", "gpt-6-luna")).second
    assertEquals(listOf(null, "function_call"), other.map { it.jsonObject.s("type") })
  }

  @Test
  fun `a replayed item keeps what the vendor reads and drops the stream's bookkeeping`() {
    val (reasoning, message, call) = accumulated(reasoningItem, messageItem, callItem)
    assertEquals(setOf("type", "id", "summary", "encrypted_content"), reasoning.keys)
    assertEquals(setOf("type", "id", "role", "phase", "content"), message.keys)
    assertEquals("commentary", message.s("phase"))
    assertEquals(setOf("type", "text"), (message["content"] as JsonArray).single().jsonObject.keys)
    assertEquals(setOf("type", "id", "call_id", "name", "arguments"), call.keys)
  }

  @Test
  fun `an answer cut while reasoning loses the reasoning it ended on`() {
    val items = accumulated(reasoningItem, messageItem, JsonObject(reasoningItem + ("id" to JsonPrimitive("rs_2"))))
    assertEquals(listOf("reasoning", "message"), items.map { it.s("type") })
  }

  @Test
  fun `reasoning without its encrypted content takes the reasoning and the ids with it`() {
    val bare = JsonObject(reasoningItem - "encrypted_content")
    val items = accumulated(bare, messageItem, callItem)
    assertEquals(listOf("message", "function_call"), items.map { it.s("type") })
    assertTrue(items.none { "id" in it })
  }

  @Test
  fun `tools go in the flat shape, strict mode off`() {
    val tool = ResponsesWire.tools(listOf(ToolSpec("memory_search", "search", obj("""{"type":"object"}""")))).single().jsonObject
    assertEquals("memory_search", tool.s("name"))
    assertEquals("false", tool.s("strict"))
    assertNull(tool["function"])
  }

  @Test
  fun `the stream gives text, reasoning, finished items and failures`() {
    assertEquals("Hi", ResponsesWire.textDelta(obj("""{"type":"response.output_text.delta","delta":"Hi"}""")))
    assertNull(ResponsesWire.textDelta(obj("""{"type":"response.reasoning_summary_text.delta","delta":"Hm"}""")))
    assertEquals("Hm", ReasoningStream.fromResponsesEvent(obj("""{"type":"response.reasoning_summary_text.delta","delta":"Hm"}""")))
    assertEquals("raw", ReasoningStream.fromResponsesEvent(obj("""{"type":"response.reasoning_text.delta","delta":"raw"}""")))
    assertEquals("\n\n", ReasoningStream.fromResponsesEvent(obj("""{"type":"response.reasoning_summary_part.added","summary_index":1}""")))
    assertNull(ReasoningStream.fromResponsesEvent(obj("""{"type":"response.reasoning_summary_part.added","summary_index":0}""")))
    assertEquals(callItem, ResponsesWire.doneItem(obj("""{"type":"response.output_item.done","item":$callItem}""")))
    assertNull(ResponsesWire.doneItem(obj("""{"type":"response.output_item.added","item":$callItem}""")))
    assertEquals("rate_limit_exceeded: slow down",
                 ResponsesWire.failure(obj("""{"type":"error","code":"rate_limit_exceeded","message":"slow down"}""")))
    assertEquals("server_error: boom",
                 ResponsesWire.failure(obj("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}}""")))
    assertNull(ResponsesWire.failure(obj("""{"type":"response.incomplete","response":{}}""")))
  }

  @Test
  fun `a finished function call is a tool call named by its call id`() {
    val calls = ToolCallAccumulator().apply {
      responsesItem(messageItem)
      responsesItem(callItem)
    }.calls()
    assertEquals(listOf(ToolCall("call_1", "memory_search", """{"query":"roles"}""")), calls)
  }

  @Test
  fun `the closing event says why the answer ended`() {
    fun closing(type: String, response: String) = StopReason.fromResponsesEvent(obj("""{"type":"$type","response":$response}"""))
    assertEquals(StopReason.Kind.END, closing("response.completed", """{"output":[$messageItem]}""")?.kind)
    assertEquals(StopReason.Kind.TOOL_USE, closing("response.completed", """{"output":[$reasoningItem,$callItem]}""")?.kind)
    val cut = closing("response.incomplete", """{"output":[],"incomplete_details":{"reason":"max_output_tokens"}}""")
    assertEquals(StopReason.Kind.LENGTH, cut?.kind)
    assertEquals("max_output_tokens", cut?.raw)
    assertEquals(StopReason.Kind.CONTENT_FILTER,
                 closing("response.incomplete", """{"output":[],"incomplete_details":{"reason":"content_filter"}}""")?.kind)
    val refused = closing("response.completed",
                          """{"output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"I can't help with that."}]}]}""")
    assertEquals(StopReason.Kind.REFUSAL, refused?.kind)
    assertEquals("I can't help with that.", refused?.explanation)
    assertTrue(refused!!.abnormal)
    assertNull(StopReason.fromResponsesEvent(obj("""{"type":"response.in_progress","response":{}}""")))
  }

  @Test
  fun `a refusal part stays in the replay so the reasoning before it keeps its following item`() {
    val refusal = obj("""{"type":"message","id":"msg_2","role":"assistant","content":[{"type":"refusal","refusal":"no"}]}""")
    val items = accumulated(reasoningItem, refusal)
    assertEquals(listOf("reasoning", "message"), items.map { it.s("type") })
    assertEquals("refusal", (items[1]["content"] as JsonArray).single().jsonObject.s("type"))
  }

  @Test
  fun `usage keeps cached and written input apart from the rest`() {
    val usage = TokenUsage.fromResponsesEvent(obj("""{"type":"response.completed","response":{"usage":{
      "input_tokens":1000,"input_tokens_details":{"cached_tokens":600,"cache_write_tokens":300},
      "output_tokens":250,"output_tokens_details":{"reasoning_tokens":200},"total_tokens":1250}}}"""))
    assertEquals(TokenUsage(inputTokens = 100, outputTokens = 250, cacheReadTokens = 600, cacheWriteTokens = 300), usage)
    assertNull(TokenUsage.fromResponsesEvent(obj("""{"type":"response.output_text.delta","delta":"x"}""")))
  }

  @Test
  fun `chat completions counts cache writes apart from input too`() {
    val usage = TokenUsage.fromOpenAiChunk(obj("""{"usage":{"prompt_tokens":1000,"completion_tokens":10,
      "prompt_tokens_details":{"cached_tokens":600,"cache_write_tokens":300}}}"""))
    assertEquals(TokenUsage(inputTokens = 100, outputTokens = 10, cacheReadTokens = 600, cacheWriteTokens = 300), usage)
  }

  @Test
  fun `the answering model is named by the response object`() {
    assertEquals("gpt-6-sol-2026-09-22",
                 ModelEcho.fromResponsesEvent(obj("""{"type":"response.created","response":{"model":"gpt-6-sol-2026-09-22"}}""")))
  }

  @Test
  fun `a whole answer gives its text and items the same way`() {
    val answer = obj("""{"status":"completed","model":"gpt-6-sol","output":[$reasoningItem,$messageItem]}""")
    assertEquals("Looking.", ResponsesWire.outputText(answer))
    assertEquals(2, ResponsesWire.outputItems(answer).size)
    assertEquals(StopReason.Kind.END, StopReason.fromResponsesBody(answer)?.kind)
  }

  @Test
  fun `items survive the thread file, in a round and on the final answer`() {
    val replay = ResponsesReplay(key, accumulated(reasoningItem, messageItem, callItem))
    val round = ToolRound("Looking.", listOf(ToolCall("call_1", "memory_search", "{}")),
                          listOf(ToolResult("call_1", "memory_search", "found")), responses = replay)
    val final = ResponsesReplay(key, accumulated(messageItem))
    val record = ChatMessageRecord(Role.ASSISTANT, "Looking. Done.", at = "2026-09-23T00:00:00Z",
                                   toolRounds = listOf(round), responses = final)
    val thread = ChatThread("t1", "2026-09-23T00:00:00Z", "2026-09-23T00:00:00Z", null, null, listOf(record))
    val back = ChatTranscriptCodec.fromJson(ChatTranscriptCodec.toJson(thread))!!.messages.single()
    assertEquals(replay, back.toolRounds.single().responses)
    assertEquals(final, back.responses)
    assertEquals(final, back.withPinned(true).responses)
    // Expanded into the request, the round carries its items and the answer keeps its own.
    val wire = ToolRounds.expand(listOf(ChatMessage("assistant", back.text, toolRounds = back.toolRounds, responses = back.responses)))
    assertEquals(replay, wire[0].responses)
    assertEquals(final, wire.last().responses)
  }

  @Test
  fun `a duplicated or branched thread keeps the tool rounds and the items`() {
    val store = HistoryStore { "2026-09-23T12:00:00Z" }
    val created = store.create(null, null)
    val round = ToolRound("Looking.", listOf(ToolCall("call_1", "memory_search", "{}")),
                          listOf(ToolResult("call_1", "memory_search", "found")))
    val final = ResponsesReplay(key, accumulated(messageItem))
    store.append(created.id, ChatMessageRecord(Role.USER, "where?", at = "2026-09-23T12:00:00Z"), cap = 100)
    store.append(created.id, ChatMessageRecord(Role.ASSISTANT, "Looking. Done.", at = "2026-09-23T12:00:01Z",
                                                toolRounds = listOf(round), responses = final), cap = 100)
    for (copy in listOf(store.duplicate(created.id)!!, store.branch(created.id, 1)!!)) {
      assertEquals(listOf(round), copy.messages.last().toolRounds)
      assertEquals(final, copy.messages.last().responses)
    }
  }

  @Test
  fun `a broken stored replay is no replay`() {
    assertNull(ResponsesReplay.fromStored(obj("""{"items":[{"type":"message"}]}""")))
    assertNull(ResponsesReplay.fromStored(obj("""{"key":"k","items":[]}""")))
  }
}
