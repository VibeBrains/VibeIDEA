// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.util.obj
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * One thinking block of an answer on Anthropic's wire, kept exactly as it streamed so it can go back where it came from.
 *
 * Two kinds of model need it back. Vendors on this wire that REQUIRE the reasoning of a tool-calling turn in the history
 * (MiMo, Kimi — [ModelQuirks.Quirk.ECHO_REASONING]) answer 400 without it. Claude reads it to keep its reasoning
 * between tool rounds, and its vendor asks for the block unmodified: a block is valid only byte for byte, signature
 * included, so nothing here is ever rewritten.
 */
data class ThinkingBlock(
  /** The streamed text: the reasoning, or its summary under `display: "summarized"`; empty under `omitted`. */
  val thinking: String? = null,
  /** The vendor's signature over the block; some Anthropic-compatible vendors send none. */
  val signature: String? = null,
  /** The opaque payload of a `redacted_thinking` block; null for an ordinary one. */
  val redactedData: String? = null,
) {
  /** The block in the shape the wire takes it back. */
  fun toWire(): JsonObject = buildJsonObject {
    if (redactedData != null) {
      put("type", "redacted_thinking")
      put("data", redactedData)
    }
    else {
      put("type", "thinking")
      put("thinking", thinking.orEmpty())
      signature?.let { put("signature", it) }
    }
  }

  /** The block for the thread file: the same fields, read back by [fromStored]. */
  fun toStored(): JsonObject = buildJsonObject {
    thinking?.let { put("thinking", it) }
    signature?.let { put("signature", it) }
    redactedData?.let { put("data", it) }
  }

  /**
   * The key of the prefix a thinking block was produced after: the system prompt, the tool set and every message before it
   *
   * Claude binds a block to the prefix it was produced with — a replayed block after a change of `system`, `tools` or an
   * earlier message is a 400 on accounts created from 31.08.2026 (platform.claude.com/docs/en/models/opus-5-5/migration-guide)
   * So a block goes back only after the same bytes it was produced after, whether it is from this turn or an earlier one:
   * A compacted history, a result cut for the thread file or a dropped image changes the prefix, and the block stays out
   * Messages are fed as the wire serialises them without the cache mark: the mark moves every turn and binds nothing
   */
  class PrefixKey(system: String, tools: String) {
    private val digest = MessageDigest.getInstance("SHA-256").apply { feed(system); feed(tools) }

    /** The key of the prefix fed so far */
    fun current(): String = (digest.clone() as MessageDigest).digest().joinToString("") { "%02x".format(it) }

    /** Extends the prefix by one message as it goes on the wire */
    fun add(message: String) = digest.feed(message)

    private fun MessageDigest.feed(text: String) {
      update(text.toByteArray(Charsets.UTF_8))
      update(0)
    }
  }

  companion object {
    /** Tolerant: a stored entry without a single field is not a block. */
    fun fromStored(element: JsonElement?): ThinkingBlock? {
      val o = element as? JsonObject ?: return null
      val block = ThinkingBlock(string(o["thinking"]), string(o["signature"]), string(o["data"]))
      return block.takeIf { it.thinking != null || it.signature != null || it.redactedData != null }
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull
  }
}

/**
 * Which thinking blocks of earlier tool rounds go back to this model on Anthropic's wire.
 *
 * A model that requires its reasoning back ([ModelQuirks.Quirk.ECHO_REASONING]: MiMo, Kimi, DeepSeek, MiniMax) gets every block it
 * produced, earlier turns included — without them the vendor answers 400
 * Claude gets every block whose prefix is byte for byte the one it was produced after ([ThinkingBlock.PrefixKey]),
 * earlier turns included: its vendor asks for them in a tool loop, keeps its reasoning coherent with them, and a prefix
 * that stops matching where a block used to be is a cache miss on everything after it
 * A block replayed after a changed prefix is a 400 on newer accounts, so a block without a matching key stays out
 * Any other vendor on this wire gets none: a block it did not ask for is a guess about its parser
 */
enum class ThinkingReplay {
  ALL, SAME_PREFIX, NONE;

  fun admits(blockKey: String?, requestKey: String): Boolean = when (this) {
    ALL -> true
    SAME_PREFIX -> blockKey != null && blockKey == requestKey
    NONE -> false
  }

  /**
   * The blocks [m] carries back in a request whose prefix key is [requestKey]
   *
   * A model that requires its reasoning back gets it from every answer, and an answer without tool calls kept only
   * the text of its reasoning: it goes back as one unsigned block, as vendors without signatures send it
   * Claude never gets such a block — its vendor takes back only what it signed
   */
  fun blocksFor(m: ChatMessage, requestKey: String): List<ThinkingBlock> = when {
    m.thinking.isNotEmpty() -> if (admits(m.thinkingKey, requestKey)) m.thinking else emptyList()
    this == ALL && m.role == ASSISTANT && !m.reasoning.isNullOrBlank() -> listOf(ThinkingBlock(thinking = m.reasoning))
    else -> emptyList()
  }

  companion object {
    private const val ASSISTANT = "assistant"
    private const val CLAUDE_PREFIX = "claude-"

    fun of(modelId: String, overrides: List<ModelQuirks.Rule> = emptyList()): ThinkingReplay = when {
      ModelQuirks.has(modelId, ModelQuirks.Quirk.ECHO_REASONING, overrides) -> ALL
      modelId.trim().lowercase().substringAfterLast('/').startsWith(CLAUDE_PREFIX) -> SAME_PREFIX
      else -> NONE
    }
  }
}

/**
 * The thinking blocks of one streamed Anthropic answer, in answer order.
 *
 * `content_block_start` opens a block (`thinking`, or `redacted_thinking` with its whole payload), `thinking_delta`
 * grows its text and `signature_delta` brings the signature. A vendor that never sends a signature leaves the block
 * without one, which is what it expects back.
 */
class ThinkingAccumulator {
  private class Pending(val redactedData: String?) {
    val text = StringBuilder()
    var signature: String? = null
  }

  private val byIndex = java.util.TreeMap<Int, Pending>()

  fun anthropicEvent(event: JsonObject) {
    val index = (event["index"] as? JsonPrimitive)?.intOrNull ?: return
    when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
      "content_block_start" -> {
        val block = event["content_block"].obj() ?: return
        when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
          "thinking" -> byIndex[index] = Pending(null).also { pending ->
            (block["thinking"] as? JsonPrimitive)?.contentOrNull?.let { pending.text.append(it) }
            (block["signature"] as? JsonPrimitive)?.contentOrNull?.let { pending.signature = it }
          }
          "redacted_thinking" -> byIndex[index] = Pending((block["data"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
      }
      "content_block_delta" -> {
        val pending = byIndex[index] ?: return
        val delta = event["delta"].obj() ?: return
        when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
          "thinking_delta" -> (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let { pending.text.append(it) }
          "signature_delta" -> (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { pending.signature = it }
        }
      }
    }
  }

  /** The blocks of the answer so far, in answer order. */
  fun blocks(): List<ThinkingBlock> = byIndex.values.map { pending ->
    if (pending.redactedData != null) ThinkingBlock(redactedData = pending.redactedData)
    else ThinkingBlock(thinking = pending.text.toString(), signature = pending.signature)
  }
}
