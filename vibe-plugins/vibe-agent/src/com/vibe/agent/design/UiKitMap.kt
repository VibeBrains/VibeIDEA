// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

/**
 * The map of what is already built, taken FROM THE CODE.
 *
 * The measurement answers «что победило на этом экране»; this answers «что вообще объявлено в
 * проекте» — the palette tokens, the class names, the exported components. Those are two different
 * questions, and only the second one prevents the tenth slightly different button from being
 * written because nobody knew the ninth existed.
 *
 * Extraction is deliberately syntactic. A real parser per framework would be a project of its own,
 * and the map is a draft for a human to correct, not a source of truth: an honest empty result is
 * better than an invented one.
 */
object UiKitMap {
  data class Entry(val name: String, val where: String)

  data class Map(val tokens: List<Entry>, val classes: List<Entry>, val components: List<Entry>) {
    val isEmpty: Boolean get() = tokens.isEmpty() && classes.isEmpty() && components.isEmpty()
  }

  private val CSS_VARIABLE = Regex("(--[a-zA-Z0-9-]{2,60})\\s*:")
  private val CSS_CLASS = Regex("\\.([a-zA-Z][a-zA-Z0-9_-]{1,60})\\s*[{,]")
  private val EXPORTED_COMPONENT = Regex("export\\s+(?:default\\s+)?(?:function|const|class)\\s+([A-Z][A-Za-z0-9_]{1,60})")

  fun scan(files: Map2): Map {
    val tokens = LinkedHashMap<String, String>()
    val classes = LinkedHashMap<String, String>()
    val components = LinkedHashMap<String, String>()
    for ((path, text) in files.entries) {
      val lower = path.lowercase()
      if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) {
        CSS_VARIABLE.findAll(text).forEach { tokens.putIfAbsent(it.groupValues[1], path) }
        CSS_CLASS.findAll(text).forEach { classes.putIfAbsent(it.groupValues[1], path) }
      }
      if (lower.endsWith(".tsx") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".js")) {
        EXPORTED_COMPONENT.findAll(text).forEach { components.putIfAbsent(it.groupValues[1], path) }
      }
    }
    return Map(
      tokens.entries.map { Entry(it.key, it.value) },
      classes.entries.map { Entry(it.key, it.value) },
      components.entries.map { Entry(it.key, it.value) },
    )
  }

  /** Kotlin's own Map is shadowed by ours; this alias keeps the signature readable. */
  typealias Map2 = kotlin.collections.Map<String, String>

  /** The document. An empty section says so out loud rather than being dropped. */
  fun render(map: Map, labels: Labels): String = buildString {
    appendLine("# " + labels.title)
    appendLine()
    appendLine(labels.preamble)
    section(labels.tokens, map.tokens, labels)
    section(labels.classes, map.classes, labels)
    section(labels.components, map.components, labels)
  }.trimEnd()

  private fun StringBuilder.section(title: String, entries: List<Entry>, labels: Labels) {
    appendLine()
    appendLine("## " + title + " (" + entries.size + ")")
    if (entries.isEmpty()) {
      appendLine(labels.empty)
      return
    }
    entries.take(MAX_PER_SECTION).forEach { appendLine("- `" + it.name + "` — " + it.where) }
    if (entries.size > MAX_PER_SECTION) appendLine(labels.more(entries.size - MAX_PER_SECTION))
  }

  interface Labels {
    val title: String
    val preamble: String
    val tokens: String
    val classes: String
    val components: String
    val empty: String
    fun more(count: Int): String
  }

  const val MAX_PER_SECTION = 100
}
