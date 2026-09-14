// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool search on our side of the wire: past a threshold the model gets one search tool instead of every schema.
 *
 * Models pick worse among many tools (Anthropic's guide names 30–50 as the point where selection degrades,
 * platform.claude.com tool-search-tool), and every schema travels in every request. OpenAI and Anthropic offer
 * server-side deferred loading, Gemini does not — three wires would behave three ways. So the search is ours
 * and wire-neutral: the model calls [NAME], we rank tools by their names and descriptions, and the found ones
 * join the offered set for the rest of the thread.
 *
 * Below the threshold nothing changes: the model sees every tool, as before. Loading a tool changes the tool
 * list and therefore the start of the request — a one-time cache reset per load, said in the feed.
 *
 * Pure: tool lists in, tool lists out.
 */
object ToolSearch {
  const val NAME = "tool_search"

  /** How many tools one search loads. */
  const val RESULTS = 5

  /** Model-facing, so English and plain. */
  val SPEC: ToolSpec = ToolSpec(
    name = NAME,
    description = "Find tools by what you need to do. Only some tools are loaded; describe the task in a few words " +
                  "(for example \"where a symbol is used\" or \"search saved memory\") and the matching tools become " +
                  "available for the rest of this conversation. Call it before saying a tool does not exist.",
    schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("query") { put("type", "string"); put("description", "What the tool should do") }
      }
      putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("query")) }
    },
  )

  fun active(all: List<ToolSpec>, threshold: Int): Boolean = all.size > threshold

  /** The tools this request carries: everything below the threshold, otherwise the search plus what was loaded. */
  fun offered(all: List<ToolSpec>, loaded: Set<String>, threshold: Int): List<ToolSpec> =
    if (!active(all, threshold)) all
    else listOf(SPEC) + all.filter { it.name in loaded }

  /**
   * The best matches for [query], best first; a tool with no word in common is not a match.
   *
   * Scoring is word overlap weighted towards the name — a name is what the tool is, a description is how it was
   * explained. Words shorter than three letters carry no meaning here and are skipped.
   */
  fun search(query: String, all: List<ToolSpec>, limit: Int = RESULTS): List<ToolSpec> {
    val wanted = words(query)
    if (wanted.isEmpty()) return emptyList()
    return all.filter { it.name != NAME }
      .map { tool ->
        val name = words(tool.name.replace('_', ' '))
        val description = words(tool.description)
        tool to (wanted.count { it in name } * NAME_WEIGHT + wanted.count { it in description })
      }
      .filter { it.second > 0 }
      .sortedByDescending { it.second }
      .take(limit)
      .map { it.first }
  }

  /** What the model reads back: the loaded tools, or a plain «nothing» so it does not invent one. */
  fun answer(found: List<ToolSpec>): String =
    if (found.isEmpty()) "No tool matches. Rephrase the task, or answer without a tool."
    else "Loaded:\n" + found.joinToString("\n") { "- ${it.name}: ${it.description}" }

  private const val NAME_WEIGHT = 3
  private const val MIN_WORD = 3

  private fun words(text: String): Set<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_WORD }.toSet()
}
