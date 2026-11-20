// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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
 * Two signs make the search worthwhile, because the two failures differ: too many tools to choose among, and a
 * request too heavy for the provider to accept. VibeIDE met the second one — a 558 KB body of 221 schemas got
 * HTTP 500 while the model's window was 14% full — so the second sign is measured in characters, not in tools.
 *
 * The agents' own hands are never deferred: a model that has to search before every edit pays a round per edit.
 * Deferral follows a sacrifice order instead — the memory servers and the person's own servers sit at the end of
 * the registry and go first, the IDE's own tools stay while they fit.
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

  /** Past this much schema text the body itself is the suspect, whatever the number of tools. */
  const val SCHEMA_CHARS_THRESHOLD = 60_000

  /**
   * The tools a model works with, never deferred.
   *
   * Read, write, replace, run: without them the agent cannot change a file at all, and a search round before
   * every edit costs a turn. They are also the ones a person watches — a write that first needs a search looks
   * like a hang.
   */
  val CORE: Set<String> = setOf(
    McpProtocol.TOOL_READ_FILE,
    McpProtocol.TOOL_WRITE_FILE,
    McpProtocol.TOOL_REPLACE_IN_FILE,
    McpProtocol.TOOL_RUN_COMMAND,
    McpProtocol.TOOL_OPEN_FILE,
    McpProtocol.TOOL_PROBLEMS,
  )

  /** Model-facing, so English and plain. */
  val SPEC: ToolSpec = ToolSpec(
    name = NAME,
    description = "Find tools by what you need to do. Only some tools are loaded; describe the task in a few words " +
                  "(for example \"where a symbol is used\" or \"search saved memory\") and the matching tools become " +
                  "available for the rest of this conversation. To load tools by exact name answer " +
                  "\"select:name1,name2\" — the deferred tools note lists the names. " +
                  "Call it before saying a tool does not exist.",
    schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("query") {
          put("type", "string"); put("description", "What the tool should do, or select:name1,name2")
        }
      }
      putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("query")) }
    },
  )

  /** Whether the search takes over: too many tools to choose among, or too heavy a body to send. */
  fun active(all: List<ToolSpec>, threshold: Int): Boolean =
    all.size > threshold || schemaChars(all) > SCHEMA_CHARS_THRESHOLD

  /** The tools this request carries: everything below the threshold, otherwise the search plus what was loaded. */
  fun offered(all: List<ToolSpec>, loaded: Set<String>, threshold: Int): List<ToolSpec> {
    val deferred = deferredNames(all, loaded, threshold)
    return if (deferred.isEmpty()) all else listOf(SPEC) + all.filter { it.name !in deferred }
  }

  /**
   * The names that do not travel; empty when the search is not needed at all.
   *
   * Victims are taken from the end of the registry while the request stays too heavy or too crowded, so the
   * servers a person added go before the IDE's own tools, and [CORE] never goes. Loaded tools are not victims:
   * the model asked for them, by name or by meaning.
   */
  fun deferredNames(all: List<ToolSpec>, loaded: Set<String>, threshold: Int): Set<String> {
    if (!active(all, threshold)) return emptySet()
    val candidates = all.filter { it.name !in CORE && it.name != NAME && it.name !in loaded }
    var count = all.size
    var chars = schemaChars(all)
    val victims = LinkedHashSet<String>()
    for (tool in candidates.asReversed()) {
      if (count <= threshold && chars <= SCHEMA_CHARS_THRESHOLD) break
      victims += tool.name
      count--
      chars -= schemaChars(listOf(tool))
    }
    return victims
  }

  /**
   * The note that tells the model which tools exist but are not loaded.
   *
   * Without the names the model invents them — «memory_read» for `memory_get` — and an invented name is worse
   * than a missing tool: it reads as a refusal from the IDE. Null when nothing is deferred.
   * [loaded] covers the tools that travel in place: they are not in [offered], but the model already has them.
   */
  fun deferralNote(all: List<ToolSpec>, offered: List<ToolSpec>, loaded: Set<String> = emptySet()): String? {
    val shown = offered.mapTo(HashSet()) { it.name }
    shown += loaded
    val deferred = all.filter { it.name !in shown && it.name != NAME }
    if (deferred.isEmpty()) return null
    return "<deferred_tools>\n" +
           "These tools exist but are not loaded. Load one by its exact name with tool_search " +
           "\"select:name1,name2\", or describe the task to find tools by meaning.\n" +
           deferred.joinToString("\n") { "- " + it.name } +
           "\n</deferred_tools>"
  }

  /**
   * The best matches for [query], best first; a tool with no word in common is not a match.
   *
   * Scoring is word overlap weighted towards the name — a name is what the tool is, a description is how it was
   * explained. Words shorter than three letters carry no meaning here and are skipped.
   * A query that starts with `select:` asks for exact names instead, and gets exactly those.
   */
  fun search(query: String, all: List<ToolSpec>, limit: Int = RESULTS): List<ToolSpec> {
    val selected = selected(query)
    if (selected.isNotEmpty()) return all.filter { it.name != NAME && it.name in selected }
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

  /** The exact names a `select:` query asks for; empty for a description. */
  fun selected(query: String): List<String> {
    val text = query.trim()
    if (!text.startsWith(SELECT, ignoreCase = true)) return emptyList()
    return text.substring(SELECT.length).split(',').map { it.trim() }.filter { it.isNotEmpty() }
  }

  /**
   * What the model reads back: the loaded tools, or a plain «nothing» so it does not invent one.
   * [missing] names the exact tools a `select:` asked for and did not get, so a typo is answered, not guessed at.
   */
  fun answer(found: List<ToolSpec>, missing: List<String> = emptyList()): String {
    val unknown = if (missing.isEmpty()) "" else "No such tool: " + missing.joinToString(", ") + ". "
    return when {
      found.isEmpty() && missing.isEmpty() -> "No tool matches. Rephrase the task, or answer without a tool."
      found.isEmpty() -> unknown + "No tool matches. Rephrase the task, or answer without a tool."
      else -> unknown + "Loaded:\n" + found.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }
  }

  /** How much text the schemas of [tools] weigh — the number a provider refuses a body over. */
  fun schemaChars(tools: List<ToolSpec>): Long =
    tools.sumOf { tool -> (tool.name.length + tool.description.length + tool.schema.toString().length).toLong() }

  private const val NAME_WEIGHT = 3
  private const val MIN_WORD = 3
  private const val SELECT = "select:"

  private fun words(text: String): Set<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_WORD }.toSet()
}
