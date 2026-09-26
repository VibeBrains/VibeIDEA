// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tools found by search mid-conversation reach Claude as a system message, not as a longer `tools` list
 *
 * A changed `tools` list changes the prompt prefix: the cache misses from the top, and every replayed thinking block
 * after it is a 400 on newer accounts. A `tool_addition` block in a system message placed after the round that found
 * the tools leaves everything before it byte for byte as it was (beta `inline-tools-2026-09-15`,
 * platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages, checked 2026-09-26)
 *
 * Pure: the route and the model in, whether it applies out; the tool definitions in, the message out
 */
object InlineTools {
  const val BETA = "inline-tools-2026-09-15"

  /** On Anthropic's own API, for the models that take mid-conversation system messages ([ModelQuirks.Quirk.INLINE_TOOL_ADDITIONS]) */
  fun supported(baseUrl: String, wire: String, quirkModelId: String, overrides: List<ModelQuirks.Rule> = emptyList()): Boolean =
    wire == ModelQuirks.WIRE_ANTHROPIC && AnthropicApi.official(baseUrl) &&
    ModelQuirks.has(quirkModelId, ModelQuirks.Quirk.INLINE_TOOL_ADDITIONS, overrides)

  /** The system message that offers [tools] from this point on; each definition is the one `tools` would carry */
  fun message(tools: List<ToolSpec>): JsonObject = buildJsonObject {
    put("role", SYSTEM)
    put("content", JsonArray(ToolCalls.anthropicTools(tools).map { definition ->
      buildJsonObject {
        put("type", "tool_addition")
        put("tool", buildJsonObject {
          put("type", "tool_definition")
          put("definition", definition)
        })
      }
    }))
  }

  private const val SYSTEM = "system"
}
