// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The chat's slash commands, described once.
 *
 * They used to be described twice: a row in the menu and a `startsWith` in the panel, and the two
 * lists were kept in step by grepping. That is a duplication with a specific failure — a command
 * that works but is invisible in the menu, or a menu entry that does nothing — and both look like
 * the feature was never finished.
 *
 * Here the table is the single source: the menu is built from it and the panel dispatches from it.
 * Parsing is pure, so «что понял чат» is testable without a window.
 */
object ChatCommands {
  /**
   * [needsArgument] decides what an empty argument means. Without it every handler answered
   * differently: some explained, some silently sent «/bg» to the model as a question.
   */
  data class Spec(val name: String, val needsArgument: Boolean, val description: () -> String)

  data class Parsed(val spec: Spec, val argument: String)

  /**
   * Descriptions are literal `t("…")` calls here rather than key strings in a field: a key hidden
   * behind a field is invisible to the catalogue gate, which then reports every one of them as
   * dead while the menu uses them.
   */
  val ALL: List<Spec> = listOf(
    Spec("/commit", false) { t("slash.command.commit") },
    Spec("/git", false) { t("slash.command.git") },
    Spec("/council", true) { t("slash.command.council") },
    Spec("/handoff", false) { t("slash.command.handoff") },
    Spec("/trace", false) { t("slash.command.trace") },
    Spec("/help", false) { t("slash.command.help") },
    Spec("/find", true) { t("slash.command.find") },
    Spec("/index", false) { t("slash.command.index") },
    Spec("/map", false) { t("slash.command.map") },
    Spec("/rules", false) { t("slash.command.rules") },
    Spec("/simplify", false) { t("slash.command.simplify") },
    Spec("/measure", true) { t("slash.command.measure") },
    Spec("/bg", true) { t("slash.command.bg") },
    Spec("/undo", false) { t("slash.command.undo") },
    Spec("/blame", true) { t("slash.command.blame") },
    Spec("/learn", false) { t("slash.command.learn") },
    Spec("/deploy", false) { t("slash.command.deploy") },
    Spec("/output", true) { t("slash.command.output") },
    Spec("/watch", true) { t("slash.command.watch") },
    Spec("/skill:", false) { t("slash.command.skill") },
  )

  /**
   * The command in this text, or null when it is an ordinary message.
   *
   * A word that merely starts like a command is NOT one: `/committed to main` is a sentence, and
   * treating it as `/commit` would swallow a message the user meant to send.
   */
  fun parse(text: String): Parsed? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return null
    val word = trimmed.substringBefore(' ')
    val spec = ALL.firstOrNull { it.name == word }
      // `/skill:id` carries its argument in the word itself; it is the one command with a colon.
      ?: ALL.firstOrNull { it.name.endsWith(":") && word.startsWith(it.name) }
      ?: return null
    val argument = if (word.startsWith(spec.name) && spec.name.endsWith(":")) trimmed.removePrefix(spec.name).trim()
                   else trimmed.removePrefix(word).trim()
    return Parsed(spec, argument)
  }

  /** True when the command was typed without the argument it cannot work without. */
  fun missesArgument(parsed: Parsed): Boolean = parsed.spec.needsArgument && parsed.argument.isEmpty()

  /** Menu rows: insertion text and the description key. `/cmd ` keeps the cursor after a space. */
  fun insertionOf(spec: Spec): String = if (spec.needsArgument || spec.name.endsWith(":")) spec.name + " " else spec.name
}
