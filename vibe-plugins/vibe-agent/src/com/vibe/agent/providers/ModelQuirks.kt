// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject

/**
 * What a particular model refuses to be asked, and how to ask it differently.
 *
 * Every vendor calls its protocol OpenAI-compatible, and every vendor is compatible up to a point.
 * A reasoning model rejects `temperature`, renames `max_tokens`, drops the system role or refuses
 * to stream at all — and the refusal arrives as HTTP 400 with a sentence about an unsupported
 * parameter, which in the chat looks exactly like «модель сломалась».
 *
 * The catalogue is pure and matched by the model ID, because the ID is all we ever know before the
 * first request. It is deliberately CONSERVATIVE: a quirk here removes or renames a field the user
 * asked for, so a wrong guess is worse than no guess — a temperature silently dropped from a model
 * that supported it changes answers nobody asked to change.
 *
 * Quirks are applied BEFORE `extraBody`, so a hand-written entry in `.vibe/providers.json` always
 * wins over this catalogue: a vendor may fix its API tomorrow, and the person who noticed must not
 * have to wait for us to notice too.
 */
object ModelQuirks {
  enum class Quirk {
    /**
     * Every sampling knob is rejected: the model decides for itself.
     *
     * Kept as an aggregate of the three below rather than replaced by them: it is the honest
     * description of the reasoning families, it is already written in people's own
     * `.vibe/modelQuirks.json`, and taking a spelling away from a config format is a way to break
     * files that were correct yesterday.
     */
    NO_SAMPLING,

    /** Only `temperature` is rejected. */
    NO_TEMPERATURE,

    /** Only `top_p` is rejected. */
    NO_TOP_P,

    /**
     * Only `top_k` is rejected — the case the aggregate could not express.
     *
     * MiniMax documents exactly this: `temperature` and `top_p` work, `top_k` and `stop_sequences`
     * are ignored. Describing it with NO_SAMPLING would take away two knobs the model accepts,
     * which is worse than describing nothing: the person set a temperature and silently did not
     * get it.
     */
    NO_TOP_K,

    /** `max_tokens` is called `max_completion_tokens` here. */
    MAX_COMPLETION_TOKENS,

    /** The system role is not accepted; the instruction has to travel as the first user message. */
    NO_SYSTEM_ROLE,

    /** Streaming is refused, so the answer arrives in one piece or not at all. */
    NO_STREAMING,

    /** Stop sequences are rejected. */
    NO_STOP,
  }

  /**
   * One catalogue line: which models, what they refuse, and a note for the IDE log.
   *
   * The note is English on purpose: it is written to the log, and the log is not interface — it is
   * read by whoever is debugging, next to hundreds of other English lines.
   */
  data class Rule(val pattern: Regex, val quirks: Set<Quirk>, val note: String)

  /**
   * The catalogue.
   *
   * Patterns are anchored at the start of the ID after the optional `vendor/` prefix used by
   * routers: `openai/o1-mini` is the same model as `o1-mini`, and a catalogue that missed one of
   * the two spellings would be a catalogue that works only on the direct provider.
   */
  val BUILT_IN: List<Rule> = listOf(
    Rule(
      Regex("^o1-(preview|mini)"),
      setOf(Quirk.NO_SAMPLING, Quirk.MAX_COMPLETION_TOKENS, Quirk.NO_SYSTEM_ROLE, Quirk.NO_STREAMING, Quirk.NO_STOP),
      "o1-preview/o1-mini: no system role, no streaming, no sampling knobs",
    ),
    Rule(
      Regex("^(o1|o3|o4)(-|$)"),
      setOf(Quirk.NO_SAMPLING, Quirk.MAX_COMPLETION_TOKENS, Quirk.NO_STOP),
      "o1/o3/o4 family: the model sets its own sampling, and the answer limit is named differently",
    ),
    Rule(
      // MiniMax's own documentation of its Anthropic-compatible API: `temperature` and `top_p`
      // work, `top_k` and `stop_sequences` are ignored. Nothing here is inferred from a symptom —
      // the reported SSE oddities are deliberately NOT encoded, because no source names the
      // events, and a quirk guessed from someone else's symptom rewrites requests blindly.
      Regex("^minimax-"),
      setOf(Quirk.NO_TOP_K, Quirk.NO_STOP),
      "minimax: top_k and stop_sequences are ignored by the Anthropic-compatible endpoint",
    ),
    Rule(
      Regex("^gpt-5"),
      setOf(Quirk.NO_SAMPLING, Quirk.MAX_COMPLETION_TOKENS),
      "gpt-5: the model sets its own sampling, and the answer limit is named differently",
    ),
  )

  /**
   * What `.vibe/modelQuirks.json` says, replacing the built-in answer for the models it matches.
   *
   * Held here rather than passed through every call site because the quirk lookup happens deep
   * inside request building, and threading a catalogue through it would put a parameter nobody
   * reads into a dozen signatures. The pure functions still accept the list, so the behaviour is
   * testable without touching this state.
   */
  @Volatile
  private var overrides: List<Rule> = emptyList()

  /** Installs what was read from disk; an empty list restores the built-in catalogue exactly. */
  fun setOverrides(rules: List<Rule>) {
    overrides = rules
  }

  /** Everything known about this model ID; an empty set for a model nobody complained about. */
  fun quirksOf(modelId: String, overrides: List<Rule> = this.overrides): Set<Quirk> {
    val id = normalize(modelId)
    if (id.isEmpty()) return emptySet()
    // A matching file entry REPLACES the built-in answer rather than adding to it — otherwise
    // there would be no way to say "this model is fine now", which is half the reason the file
    // exists: a vendor fixing its API must not need an IDE release to be believed.
    overrides.firstOrNull { it.pattern.containsMatchIn(id) }?.let { return it.quirks }
    return BUILT_IN.filter { it.pattern.containsMatchIn(id) }.flatMap { it.quirks }.toSet()
  }

  /** The human explanation, for the log line that says why the request was not sent as written. */
  fun noteOf(modelId: String, overrides: List<Rule> = this.overrides): String? {
    val id = normalize(modelId)
    overrides.firstOrNull { it.pattern.containsMatchIn(id) }?.let { return it.note }
    return BUILT_IN.firstOrNull { it.pattern.containsMatchIn(id) }?.note
  }

  /** Where the answer for this model came from — the one thing «почему пропала temperature» needs. */
  fun sourceOf(modelId: String, overrides: List<Rule> = this.overrides): String? {
    val id = normalize(modelId)
    if (id.isEmpty()) return null
    if (overrides.any { it.pattern.containsMatchIn(id) }) return "modelQuirks.json"
    return if (BUILT_IN.any { it.pattern.containsMatchIn(id) }) "built-in" else null
  }

  fun has(modelId: String, quirk: Quirk): Boolean = quirk in quirksOf(modelId)

  fun supportsStreaming(modelId: String): Boolean = !has(modelId, Quirk.NO_STREAMING)

  /**
   * The request body, rewritten to what this model actually accepts.
   *
   * Renaming keeps the VALUE: the user asked for a limit, and dropping it because the field moved
   * would replace their number with the provider's default without saying so.
   */
  fun applyToBody(modelId: String, body: JsonObject): JsonObject {
    val quirks = quirksOf(modelId)
    if (quirks.isEmpty()) return body
    val fields = LinkedHashMap(body)
    if (Quirk.NO_SAMPLING in quirks || Quirk.NO_TEMPERATURE in quirks) fields.remove("temperature")
    if (Quirk.NO_SAMPLING in quirks || Quirk.NO_TOP_P in quirks) fields.remove("top_p")
    if (Quirk.NO_SAMPLING in quirks || Quirk.NO_TOP_K in quirks) fields.remove("top_k")
    if (Quirk.NO_STOP in quirks) {
      fields.remove("stop")
    }
    if (Quirk.MAX_COMPLETION_TOKENS in quirks) {
      fields.remove("max_tokens")?.let { fields["max_completion_tokens"] = it }
    }
    if (Quirk.NO_STREAMING in quirks) {
      fields.remove("stream")
    }
    return JsonObject(fields)
  }

  /**
   * The messages, rewritten the same way.
   *
   * A model without a system role gets the instruction as the first user message rather than
   * losing it: the system prompt is where the rules of the whole session live, and silently
   * dropping it produces an agent that ignores the project — with nothing in the log to explain it.
   */
  fun applyToMessages(modelId: String, messages: List<ChatMessage>): List<ChatMessage> {
    if (!has(modelId, Quirk.NO_SYSTEM_ROLE)) return messages
    val system = messages.filter { it.role == "system" }
    if (system.isEmpty()) return messages
    val rest = messages.filterNot { it.role == "system" }
    val folded = ChatMessage("user", system.joinToString("\n\n") { it.text })
    return listOf(folded) + rest
  }

  /** `openai/o1-mini` and `o1-mini` are the same model; a router prefix must not hide a quirk. */
  private fun normalize(modelId: String): String {
    val trimmed = modelId.trim().lowercase()
    return if ('/' in trimmed) trimmed.substringAfterLast('/') else trimmed
  }
}
