// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fields of a model's `extraBody` that the vendor is known to refuse — found before the request, not by its 400.
 *
 * `extraBody` goes into the request last and on purpose wins over the quirk catalogue: a person who wrote a field knows
 * their route better than a rule by name. The price of that is that a field the model rejects goes out as written, and
 * the chat shows only the vendor's 400. The doctor says it instead, with the rule that would have caught it.
 *
 * Pure: the model's id, its wire and its `extraBody` in, the conflicts out.
 */
object ExtraBodyConflicts {
  enum class Reason {
    /** A sampling knob on a model that sets its own sampling. */
    SAMPLING,

    /** A token budget for thinking on a model that takes only the adaptive mode. */
    BUDGET,

    /** The explicit reasoning switch on a model whose reasoning cannot be switched off. */
    SWITCH,

    /** Forced tool use on a model that refuses it. */
    FORCED_TOOL,

    /**
     * The vendor's own fallback on Anthropic's wire: a refused turn is answered by another model inside one response
     * That answer carries a `fallback` block the next request must send back at the same place, or replayed thinking
     * around it is a 400; this client keeps no such block and retries on its own chain instead
     */
    SERVER_FALLBACK,

    /** `thinking: {"type": "between_tools"}` with another field of `thinking` beside it: the type takes none */
    BETWEEN_TOOLS_FIELD,

    /** `between_tools` with effort `xhigh` or `max`: the vendor takes it at `low`, `medium` and `high` only */
    BETWEEN_TOOLS_EFFORT,

    /** `between_tools` on a model without the mode: a vendor that does not name the type refuses it */
    BETWEEN_TOOLS_MODEL,
  }

  data class Conflict(val field: String, val reason: Reason)

  fun of(modelId: String, wire: String, extraBody: JsonObject?, overrides: List<ModelQuirks.Rule> = emptyList()): List<Conflict> {
    if (extraBody == null || extraBody.isEmpty()) return emptyList()
    val quirks = ModelQuirks.quirksOf(modelId, overrides)
    val result = ArrayList<Conflict>()
    val all = ModelQuirks.Quirk.NO_SAMPLING in quirks
    if ((all || ModelQuirks.Quirk.NO_TEMPERATURE in quirks) && TEMPERATURE in extraBody) result.add(Conflict(TEMPERATURE, Reason.SAMPLING))
    if ((all || ModelQuirks.Quirk.NO_TOP_P in quirks) && TOP_P in extraBody) result.add(Conflict(TOP_P, Reason.SAMPLING))
    if ((all || ModelQuirks.Quirk.NO_TOP_K in quirks) && TOP_K in extraBody) result.add(Conflict(TOP_K, Reason.SAMPLING))
    val thinkingType = ((extraBody[THINKING] as? JsonObject)?.get(TYPE) as? JsonPrimitive)?.contentOrNull
    if (wire == ModelQuirks.WIRE_ANTHROPIC) {
      if (ModelQuirks.Quirk.ADAPTIVE_THINKING in quirks && thinkingType == ENABLED) result.add(Conflict("$THINKING.$TYPE", Reason.BUDGET))
      val noSwitch = ModelQuirks.Quirk.THINKING_ALWAYS_ON in quirks || ModelQuirks.Quirk.OFF_THINKING_BETWEEN_TOOLS in quirks
      if (noSwitch && thinkingType == DISABLED) result.add(Conflict("$THINKING.$TYPE", Reason.SWITCH))
      if (thinkingType == BETWEEN_TOOLS) result.addAll(betweenTools(extraBody, quirks))
    }
    val effort = (extraBody[REASONING_EFFORT] as? JsonPrimitive)?.contentOrNull
    if (wire == ModelQuirks.WIRE_OPENAI && ModelQuirks.Quirk.THINKING_ALWAYS_ON in quirks && effort == NONE) {
      result.add(Conflict(REASONING_EFFORT, Reason.SWITCH))
    }
    val responsesEffort = ((extraBody[REASONING] as? JsonObject)?.get(EFFORT) as? JsonPrimitive)?.contentOrNull
    if (wire == ModelQuirks.WIRE_OPENAI_RESPONSES && ModelQuirks.Quirk.THINKING_ALWAYS_ON in quirks && responsesEffort == NONE) {
      result.add(Conflict("$REASONING.$EFFORT", Reason.SWITCH))
    }
    if (wire == ModelQuirks.WIRE_ANTHROPIC && FALLBACKS in extraBody) result.add(Conflict(FALLBACKS, Reason.SERVER_FALLBACK))
    if (ModelQuirks.Quirk.NO_FORCED_TOOL_CHOICE in quirks) forcedToolChoice(wire, extraBody[TOOL_CHOICE])?.let { result.add(it) }
    return result
  }

  /**
   * A forced tool choice in the spelling of each wire
   * Anthropic: an object with `type` `any` or `tool`
   * OpenAI chat/completions: the string `required` or an object with `type` `function`
   */
  private fun forcedToolChoice(wire: String, choice: kotlinx.serialization.json.JsonElement?): Conflict? = when (wire) {
    ModelQuirks.WIRE_ANTHROPIC -> {
      val type = ((choice as? JsonObject)?.get(TYPE) as? JsonPrimitive)?.contentOrNull
      if (type in FORCED_CHOICES) Conflict("$TOOL_CHOICE.$TYPE", Reason.FORCED_TOOL) else null
    }
    ModelQuirks.WIRE_OPENAI -> when {
      (choice as? JsonPrimitive)?.contentOrNull == REQUIRED -> Conflict(TOOL_CHOICE, Reason.FORCED_TOOL)
      ((choice as? JsonObject)?.get(TYPE) as? JsonPrimitive)?.contentOrNull == FUNCTION -> Conflict("$TOOL_CHOICE.$TYPE", Reason.FORCED_TOOL)
      else -> null
    }
    else -> null
  }

  /**
   * What `between_tools` refuses on Sonnet 5.5 (platform.claude.com/docs/en/models/sonnet-5-5/whats-new-sonnet-5-5):
   * «`between_tools` takes no other field: `display`, `budget_tokens`, or `block_binding` sent with it returns a 400»,
   * and at `xhigh` or `max` effort the request is a 400 as well
   */
  private fun betweenTools(extraBody: JsonObject, quirks: Set<ModelQuirks.Quirk>): List<Conflict> {
    val result = ArrayList<Conflict>()
    if (ModelQuirks.Quirk.OFF_THINKING_BETWEEN_TOOLS !in quirks) result.add(Conflict("$THINKING.$TYPE", Reason.BETWEEN_TOOLS_MODEL))
    val thinking = extraBody[THINKING] as JsonObject
    thinking.keys.filter { it != TYPE }.sorted().forEach { result.add(Conflict("$THINKING.$it", Reason.BETWEEN_TOOLS_FIELD)) }
    val effort = ((extraBody[OUTPUT_CONFIG] as? JsonObject)?.get(EFFORT) as? JsonPrimitive)?.contentOrNull
    if (effort in DEEP_EFFORTS) result.add(Conflict("$OUTPUT_CONFIG.$EFFORT", Reason.BETWEEN_TOOLS_EFFORT))
    return result
  }

  private const val TEMPERATURE = "temperature"
  private const val TOP_P = "top_p"
  private const val TOP_K = "top_k"
  private const val THINKING = "thinking"
  private const val TYPE = "type"
  private const val ENABLED = "enabled"
  private const val DISABLED = "disabled"
  private const val BETWEEN_TOOLS = "between_tools"
  private const val OUTPUT_CONFIG = "output_config"
  private val DEEP_EFFORTS = setOf("xhigh", "max")
  private const val REASONING_EFFORT = "reasoning_effort"
  private const val REASONING = "reasoning"
  private const val EFFORT = "effort"
  private const val NONE = "none"
  private const val TOOL_CHOICE = "tool_choice"
  private const val FALLBACKS = "fallbacks"
  private val FORCED_CHOICES = setOf("any", "tool")
  private const val REQUIRED = "required"
  private const val FUNCTION = "function"
}
