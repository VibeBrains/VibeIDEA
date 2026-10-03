// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tool call the model wrote as text in its answer, in its family's own markup, instead of in the wire's field
 *
 * Every open model is trained on a chat template that writes calls in markup of its own, and the server is what turns
 * that markup into `tool_calls`: a server without the family's parser, or a model that slips, leaves the markup
 * in the answer. Read as the answer, it is shown as fragments and no tool runs
 *
 * Which markup it is follows from the text, not from the model's name: the same family stands behind many names and
 * many hosts, and DeepSeek's markup has come from a Qwen. What makes it a call is the request: it offered tools,
 * and the markup names one of them. Markup inside a code block, or naming no offered tool, is text the model meant
 *
 * The forms ([TextToolCallFormats]) are shared with VibeIDE in `testVectors/textToolCalls.json` of the set
 * Pure: the text and the offered tools in, the calls and the answer around them out
 */
object TextToolCalls {
  data class Call(val name: String, val arguments: JsonObject)

  enum class Outcome {
    /** The markup became calls of offered tools */
    CALLS,

    /** Markup that names an offered tool but does not read: a call that failed, the model is asked to repeat it */
    UNPARSED,

    /** No call after all: no markup, markup in a code block, or markup that names no offered tool */
    TEXT,
  }

  /** [answer] is the text without the markup for calls, the words before it when unparsed, the whole text otherwise */
  data class Read(val outcome: Outcome, val calls: List<Call>, val answer: String, val format: String?)

  /** Calls read from markup and the text left around them */
  internal data class Found(val calls: List<Call>, val rest: String)

  /** Tool name → its JSON schema, which types the values a markup writes without a type */
  fun interface Schemas {
    fun of(tool: String): JsonObject?
  }

  /** One family's markup: where it opens and how its calls read */
  internal interface Format {
    /** The name the log and the vectors know it by */
    val id: String

    /** Literal openers a stream cut after their first characters waits for */
    val openers: List<String>

    /**
     * Where this markup opens in [text] at or after [from], or -1
     * [atAnswerStart]: [text] begins the answer, nothing but blanks came before it; forms that are the whole answer need it
     */
    fun open(text: CharSequence, from: Int, atAnswerStart: Boolean): Int

    /** The calls in [markup], which starts at the opener, and the text around them; null when it does not read */
    fun read(markup: String, schemas: Schemas): Found?
  }

  internal val FORMATS: List<Format> = TextToolCallFormats.ALL

  /** Every literal a cut stream may be in the middle of: the openers and a code fence */
  private val WAIT_FOR: List<String> = FORMATS.flatMap { it.openers } + FENCE

  private const val FENCE = "```"

  /**
   * Where the text stands with respect to code, which markup inside is not a call: a fenced block, an inline code span
   * The state after a text is the state before the next piece of a stream
   */
  data class Code(val inFence: Boolean = false, val inSpan: Boolean = false, val atLineStart: Boolean = true) {
    val inside: Boolean get() = inFence || inSpan

    /** The state after [text]: a fence opens a line (maybe indented), a run of ticks elsewhere opens or closes a span */
    fun after(text: CharSequence): Code {
      var fence = inFence
      var span = inSpan
      var lineStart = atLineStart
      var i = 0
      while (i < text.length) {
        val c = text[i]
        when {
          c == '\n' -> {
            lineStart = true
            span = false
          }
          lineStart && (c == ' ' || c == '\t') -> Unit
          c == '`' -> {
            var run = i
            while (run < text.length && text[run] == '`') run++
            if (lineStart && run - i >= FENCE.length) fence = !fence
            else if (!fence) span = !span
            lineStart = false
            i = run
            continue
          }
          else -> lineStart = false
        }
        i++
      }
      return Code(fence, span, lineStart)
    }
  }

  /**
   * Where call markup starts in [text], or -1; markup inside a code block or an inline code span is not a call
   * [code] and [atAnswerStart] are the state before [text], for a stream read in pieces
   */
  fun start(text: CharSequence, code: Code = Code(), atAnswerStart: Boolean = true): Int =
    first(text, code, atAnswerStart)?.second ?: -1

  /** The format that opens first outside code, and where */
  private fun first(text: CharSequence, code: Code, atAnswerStart: Boolean): Pair<Format, Int>? {
    var best: Pair<Format, Int>? = null
    for (format in FORMATS) {
      var from = 0
      while (true) {
        val at = format.open(text, from, atAnswerStart)
        if (at < 0 || (best != null && at >= best.second)) break
        if (!code.after(text.subSequence(0, at)).inside) {
          best = format to at
          break
        }
        from = at + 1
      }
    }
    return best
  }

  /**
   * Length of the end of [text] that may still grow into an opener or a fence: it waits for the next piece
   * A `<` not yet closed by `>` waits too: markup whose markers were lost opens with `<` and a space
   */
  fun openTail(text: CharSequence): Int {
    var keep = 0
    for (literal in WAIT_FOR) {
      // A whole opener waits too: several are only the head of what the format's pattern needs
      for (length in minOf(literal.length, text.length) downTo 1) {
        if (length <= keep) break
        if (text.regionMatches(text.length - length, literal, 0, length)) {
          keep = length
          break
        }
      }
    }
    val lt = text.lastIndexOf('<')
    if (lt >= 0 && text.indexOf('>', lt) < 0 && text.length - lt <= TAG_PREFIX) keep = maxOf(keep, text.length - lt)
    return keep
  }

  /** Longest start of a tag worth waiting for: `<`, markers, spaces and a wrapper's name */
  private const val TAG_PREFIX = 40

  /** How much of an answer that opens with `{` or `[` waits before it is surely not calls: a long tool name fits */
  private const val ANSWER_OPENING = 80

  /** Whether the answer so far, blanks aside, opens like the forms that are the whole answer and is still short */
  fun mayOpenAnswer(text: CharSequence): Boolean {
    val opening = text.trimStart()
    if (opening.length > ANSWER_OPENING) return false
    return opening.isEmpty() || opening[0] == '{' || opening[0] == '[' || "<|python_start|>".startsWith(opening.take(16))
  }

  private fun CharSequence.regionMatches(at: Int, other: String, otherAt: Int, length: Int): Boolean {
    for (i in 0 until length) if (this[at + i] != other[otherAt + i]) return false
    return true
  }

  /**
   * The whole answer read for calls to the [offered] tools, each name with its parameters' JSON schema
   * A name is matched as written, then without the `functions.` prefix (Kimi, harmony) and the `namespace::` of V4.1
   */
  fun parse(text: String, offered: Map<String, JsonObject>): Read {
    val (opened, at) = first(text, Code(), atAnswerStart = true)
                       ?: return Read(Outcome.TEXT, emptyList(), text, null)
    val before = text.substring(0, at)
    val markup = text.substring(at)
    val schemas = Schemas { tool -> resolve(tool, offered)?.let(offered::get) }
    val atAnswerStart = before.isBlank()
    // Formats that share an opener (`<tool_call>` opens several families) are told apart by what reads
    val candidates = listOf(opened) + FORMATS.filter { it !== opened && it.open(markup, 0, atAnswerStart) == 0 }
    for (format in candidates) {
      val found = format.read(markup, schemas) ?: continue
      // A call to a tool that was not offered is the model writing about one, not calling it
      val calls = found.calls.map { call ->
        call.copy(name = resolve(call.name, offered) ?: return Read(Outcome.TEXT, emptyList(), text, format.id))
      }
      return Read(Outcome.CALLS, calls, (before + found.rest).trim(), format.id)
    }
    // Markup that names none of the tools is the model writing about markup, not calling
    if (offered.keys.none { markup.contains(it) }) return Read(Outcome.TEXT, emptyList(), text, opened.id)
    return Read(Outcome.UNPARSED, emptyList(), before.trim(), opened.id)
  }

  /** The offered tool [name] means, or null when it means none */
  private fun resolve(name: String, offered: Map<String, JsonObject>): String? =
    sequenceOf(name, name.removePrefix("functions."), name.substringAfterLast("::")).firstOrNull { it in offered }

  /**
   * A value the markup marks as not a string, as JSON; null when it is not JSON
   * kotlinx takes a bare word for a literal, so a literal is checked here: a number, `true`, `false` or `null` only
   */
  internal fun jsonValue(value: String): JsonElement? {
    val element = runCatching { Json.parseToJsonElement(value.trim()) }.getOrNull() ?: return null
    if (element !is JsonPrimitive || element.isString || element is JsonNull) return element
    val literal = element.content
    return element.takeIf { literal == "true" || literal == "false" || literal.toDoubleOrNull() != null }
  }

  /** A JSON object from [text], or null when it is not one */
  internal fun jsonObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull() as? JsonObject
}

/**
 * Holds back the part of a streamed answer that turned out to be call markup ([TextToolCalls])
 *
 * The markup has to be caught before it reaches the feed, and it arrives cut between chunks, as `<think>` does
 * ([InlineThinking]): text that is surely not markup goes on at once, a tail that may still become an opener waits for
 * the next chunk, and from the first opener outside code everything is kept for [finish]
 */
class ToolMarkupFilter(private val onText: (String) -> Unit) {
  private val pending = StringBuilder()
  private var holding = false
  private var code = TextToolCalls.Code()
  private var atAnswerStart = true

  fun accept(delta: String) {
    if (delta.isEmpty()) return
    pending.append(delta)
    if (holding) return
    val at = TextToolCalls.start(pending, code, atAnswerStart)
    if (at >= 0) {
      emit(pending.substring(0, at))
      pending.delete(0, at)
      holding = true
      return
    }
    // An answer that opens like JSON or a list may still become calls: it waits until it has said enough to tell
    if (atAnswerStart && TextToolCalls.mayOpenAnswer(pending)) return
    val keep = TextToolCalls.openTail(pending)
    if (keep < pending.length) {
      emit(pending.substring(0, pending.length - keep))
      pending.delete(0, pending.length - keep)
    }
  }

  /** The held markup, or null when the answer had none; with none, the last tail goes out as text */
  fun finish(): String? {
    val rest = pending.toString()
    pending.setLength(0)
    if (holding) return rest
    emit(rest)
    return null
  }

  private fun emit(text: String) {
    if (text.isEmpty()) return
    code = code.after(text)
    atAnswerStart = atAnswerStart && text.isBlank()
    onText(text)
  }
}
