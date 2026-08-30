// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.handoff

/**
 * Unfinished work handed over by FORM rather than «на словах».
 *
 * Work stops mid-way all the time: the context ran out, the day ended, the model started guessing.
 * What is normally left behind is a chat that trails off — and whoever picks it up (a colleague,
 * another agent, the same person on Monday) starts by rebuilding the state of the world from
 * scrolling. The expensive part is not the code that was written, it is what was LEARNED: which
 * approach was already tried and why it failed.
 *
 * The form is deliberately small. A handoff nobody fills in is worse than none, so it asks only
 * for what cannot be recovered from the diff: the goal, what is left, the traps, and how to check.
 */
object HandoffForm {
  data class Handoff(
    val goal: String,
    val done: List<String> = emptyList(),
    val remaining: List<String> = emptyList(),
    /** What was tried and did not work — the one thing a diff never shows. */
    val traps: List<String> = emptyList(),
    val touchedFiles: List<String> = emptyList(),
    val howToVerify: String? = null,
  )

  data class Labels(
    val title: String,
    val goal: String,
    val done: String,
    val remaining: String,
    val traps: String,
    val files: String,
    val verify: String,
    val empty: String,
  )

  /** Sections that are missing — named so the form is filled, not silently half-empty. */
  fun gaps(handoff: Handoff): List<String> = buildList {
    if (handoff.goal.isBlank()) add(GOAL)
    if (handoff.remaining.isEmpty()) add(REMAINING)
    if (handoff.howToVerify.isNullOrBlank()) add(VERIFY)
  }

  fun render(handoff: Handoff, labels: Labels): String = buildString {
    appendLine("# ${labels.title}")
    appendLine()
    appendLine("## ${labels.goal}")
    appendLine(handoff.goal.ifBlank { labels.empty })
    section(labels.done, handoff.done, labels)
    section(labels.remaining, handoff.remaining, labels)
    section(labels.traps, handoff.traps, labels)
    section(labels.files, handoff.touchedFiles, labels)
    appendLine()
    appendLine("## ${labels.verify}")
    appendLine(handoff.howToVerify?.takeIf { it.isNotBlank() } ?: labels.empty)
  }.trimEnd()

  private fun StringBuilder.section(title: String, items: List<String>, labels: Labels) {
    appendLine()
    appendLine("## $title")
    if (items.isEmpty()) appendLine(labels.empty) else items.forEach { appendLine("- $it") }
  }

  const val GOAL = "goal"
  const val REMAINING = "remaining"
  const val VERIFY = "verify"
}
