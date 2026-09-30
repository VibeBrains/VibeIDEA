// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tool call the model wrote as text in its answer, in DeepSeek's markup (DSML), instead of in `tool_calls`
 *
 * DeepSeek's own template writes calls as `<｜DSML｜function_calls>` with `<｜DSML｜invoke name="…">` and
 * `<｜DSML｜parameter name="…" string="true|false">` inside (encoding_dsv32.py of deepseek-ai/DeepSeek-V3.2)
 * Sometimes that markup reaches the answer instead of the wire's field, and with the markers gone on the way:
 * `< invoke name="git_state">` and `</ parameter>`. Read as the answer, it is shown as fragments and no tool runs
 *
 * The form is shared with VibeIDE in `testVectors/dsmlToolCalls.json` of the set
 * Pure: the text in, the calls and the answer around them out
 */
object DsmlToolCalls {
  data class Call(val name: String, val arguments: JsonObject)

  /** The calls in order and the answer without the markup; [calls] is empty when the markup did not parse */
  data class Parsed(val calls: List<Call>, val answer: String, val parsed: Boolean)

  /**
   * Vendor markers around a tag name: `｜DSML｜`, fullwidth pipes only
   * An ASCII pipe pair would also match inside a value — `cat a |sort| uniq` — and cut the command
   */
  private val MARKER = Regex("｜{1,4}\\p{L}[\\p{L}\\p{N}_-]*｜{1,4}")

  /** Tag names the markup is made of: the call, its parameters and the wrappers vendors put around calls */
  private const val NAMES = "function_calls|tool_calls|calls|invoke|parameter"

  /** Space left after `<` or `</` once the markers were lost */
  private val SPACED = Regex("<\\s*(/?)\\s*($NAMES)\\b")

  /** Where the markup begins, markers or not: the first tag of the call or its wrapper */
  private val START = Regex("<\\s*(?:｜{1,4}\\p{L}[\\p{L}\\p{N}_-]*｜{1,4}\\s*)?(?:function_calls|tool_calls|calls|invoke)\\b")

  private val WRAPPER = Regex("</?(?:function_calls|tool_calls|calls)\\s*>")
  private val INVOKE = Regex("<invoke\\s+name=\"([^\"]+)\"\\s*>(.*?)</invoke\\s*>", RegexOption.DOT_MATCHES_ALL)
  private val PARAMETER = Regex(
    "<parameter\\s+name=\"([^\"]+)\"(?:\\s+string=\"(true|false)\")?\\s*>(.*?)</parameter\\s*>", RegexOption.DOT_MATCHES_ALL)

  private val json = Json

  /**
   * A `string="false"` value as JSON, or null when it is not JSON
   * The parser takes a bare word as a literal, so a literal is checked here: a number, `true`, `false` or `null` only
   */
  private fun jsonValue(value: String): JsonElement? {
    val element = runCatching { json.parseToJsonElement(value.trim()) }.getOrNull() ?: return null
    if (element !is JsonPrimitive || element.isString || element is kotlinx.serialization.json.JsonNull) return element
    val literal = element.content
    return element.takeIf { literal == "true" || literal == "false" || literal.toDoubleOrNull() != null }
  }

  /** Where the markup starts in [text], or -1: prose that mentions a tag without writing one is not a call */
  fun start(text: CharSequence): Int = START.find(text)?.range?.first ?: -1

  /** The canonical tags: markers removed, spaces after `<` and `</` closed up */
  fun normalize(markup: String): String =
    SPACED.replace(MARKER.replace(markup, "")) { "<" + it.groupValues[1] + it.groupValues[2] }

  /** The whole answer read for calls; text without markup comes back as the answer, unparsed and with no calls */
  fun parse(text: String): Parsed {
    val at = start(text)
    if (at < 0) return Parsed(emptyList(), text.trim(), parsed = false)
    val before = text.substring(0, at)
    val markup = normalize(text.substring(at))
    val calls = ArrayList<Call>()
    for (invoke in INVOKE.findAll(markup)) {
      val arguments = LinkedHashMap<String, JsonElement>()
      for (parameter in PARAMETER.findAll(invoke.groupValues[2])) {
        val value = parameter.groupValues[3]
        arguments[parameter.groupValues[1]] =
          if (parameter.groupValues[2] == "false") jsonValue(value) ?: return Parsed(emptyList(), before.trim(), parsed = false)
          else JsonPrimitive(value)
      }
      calls += Call(invoke.groupValues[1], JsonObject(arguments))
    }
    if (calls.isEmpty()) return Parsed(emptyList(), before.trim(), parsed = false)
    val after = WRAPPER.replace(INVOKE.replace(markup, ""), "")
    val answer = (before + after).trim()
    return Parsed(calls, answer, parsed = true)
  }
}

/**
 * Holds back the part of a streamed answer that turned out to be call markup ([DsmlToolCalls])
 *
 * The markup has to be caught before it reaches the feed, and it arrives cut between chunks, as `<think>` does
 * ([InlineThinking]): text that is surely not markup goes on at once, a tail that may still start a tag waits for the
 * next chunk, and from the first tag of a call everything is kept for [finish]
 */
class ToolMarkupFilter(private val onText: (String) -> Unit) {
  private val pending = StringBuilder()
  private var holding = false

  fun accept(delta: String) {
    if (delta.isEmpty()) return
    pending.append(delta)
    if (holding) return
    val at = DsmlToolCalls.start(pending)
    if (at >= 0) {
      emit(pending.substring(0, at))
      pending.delete(0, at)
      holding = true
      return
    }
    val keep = openTail(pending)
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
    if (text.isNotEmpty()) onText(text)
  }

  private companion object {
    /** Longest start of a tag worth waiting for: `<`, markers, spaces and `function_calls` */
    const val TAG_PREFIX = 40

    /** The tail after the last `<` that has not closed and is short enough to still become a tag */
    fun openTail(text: CharSequence): Int {
      val lt = text.lastIndexOf('<')
      if (lt < 0) return 0
      val tail = text.length - lt
      return if (tail <= TAG_PREFIX && text.indexOf('>', lt) < 0) tail else 0
    }
  }
}
