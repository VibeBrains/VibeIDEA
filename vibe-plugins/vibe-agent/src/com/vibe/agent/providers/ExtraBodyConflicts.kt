// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
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
      if (ModelQuirks.Quirk.THINKING_ALWAYS_ON in quirks && thinkingType == DISABLED) result.add(Conflict("$THINKING.$TYPE", Reason.SWITCH))
    }
    val effort = (extraBody[REASONING_EFFORT] as? JsonPrimitive)?.contentOrNull
    if (wire == ModelQuirks.WIRE_OPENAI && ModelQuirks.Quirk.THINKING_ALWAYS_ON in quirks && effort == NONE) {
      result.add(Conflict(REASONING_EFFORT, Reason.SWITCH))
    }
    val responsesEffort = ((extraBody[REASONING] as? JsonObject)?.get(EFFORT) as? JsonPrimitive)?.contentOrNull
    if (wire == ModelQuirks.WIRE_OPENAI_RESPONSES && ModelQuirks.Quirk.THINKING_ALWAYS_ON in quirks && responsesEffort == NONE) {
      result.add(Conflict("$REASONING.$EFFORT", Reason.SWITCH))
    }
    if (wire == ModelQuirks.WIRE_ANTHROPIC && ModelQuirks.Quirk.NO_FORCED_TOOL_CHOICE in quirks) {
      val choice = ((extraBody[TOOL_CHOICE] as? JsonObject)?.get(TYPE) as? JsonPrimitive)?.contentOrNull
      if (choice in FORCED_CHOICES) result.add(Conflict("$TOOL_CHOICE.$TYPE", Reason.FORCED_TOOL))
    }
    return result
  }

  private const val TEMPERATURE = "temperature"
  private const val TOP_P = "top_p"
  private const val TOP_K = "top_k"
  private const val THINKING = "thinking"
  private const val TYPE = "type"
  private const val ENABLED = "enabled"
  private const val DISABLED = "disabled"
  private const val REASONING_EFFORT = "reasoning_effort"
  private const val REASONING = "reasoning"
  private const val EFFORT = "effort"
  private const val NONE = "none"
  private const val TOOL_CHOICE = "tool_choice"
  private val FORCED_CHOICES = setOf("any", "tool")
}
