// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.handoff

/**
 * «Сделал» — не факт, а обещание, когда речь о том, что можно УВИДЕТЬ.
 *
 * A model cannot look at the screen. It can be certain the button is centred and be wrong, and the
 * cost of that mistake is paid by whoever opens the app expecting finished work. So when a change
 * touches something visible, the turn ends with a short list of what to open and what to look at —
 * not a promise that it is fine.
 *
 * The list is short by design: a checklist of fifteen items is a checklist nobody reads. It names
 * the files that changed and the states people forget — empty, error, narrow screen — because
 * those are exactly the three that a model never renders in its head.
 */
object EyesChecklist {
  /** Extensions whose change is visible to a person. */
  private val VISIBLE_EXTENSIONS = setOf(
    "tsx", "jsx", "vue", "svelte", "html", "htm", "css", "scss", "sass", "less", "styl",
  )

  /** Files whose NAME says they are interface, whatever the extension. */
  private val VISIBLE_MARKERS = listOf("component", "widget", "screen", "page", "view", "panel", "dialog", "theme")

  fun isVisible(path: String): Boolean {
    val lower = path.lowercase()
    // Checked FIRST: `Button.test.tsx` ends in a visible extension while being a test, and asking
    // someone to «посмотреть глазами» on a test file is how a checklist loses its credibility.
    if (lower.contains(".test.") || lower.contains(".spec.")) return false
    val extension = lower.substringAfterLast('.', "")
    if (extension in VISIBLE_EXTENSIONS) return true
    return VISIBLE_MARKERS.any { lower.contains(it) }
  }

  fun visibleFiles(paths: Collection<String>): List<String> = paths.filter { isVisible(it) }.sorted()

  /**
   * The checklist for the feed. [states] are the situations a model never renders in its head —
   * they come from the catalogue so they can be translated, and the file list is capped so the
   * checklist stays readable.
   */
  fun render(files: List<String>, states: List<String>, header: String, more: (Int) -> String, limit: Int = MAX_FILES): String {
    if (files.isEmpty()) return ""
    return buildString {
      appendLine(header)
      files.take(limit).forEach { appendLine("- $it") }
      if (files.size > limit) appendLine("- " + more(files.size - limit))
      states.forEach { appendLine("- $it") }
    }.trimEnd()
  }

  const val MAX_FILES = 5
}
