// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.terse

/**
 * Terse replies: a style instruction that cuts the filler out of a model's answers and keeps every technical fact
 *
 * The text lives in the shared set (`terse/replies.md`), read from the build by both products, so one level means one
 * instruction in VibeIDE and here; it is adapted from the MIT-licensed Caveman skill, whose notice the file carries
 * Pure: the file's text and the level come in, the text to send comes out
 */
object TerseReplies {
  /** Where the instruction lives in the shared set */
  const val FILE = "terse/replies.md"

  /** The levels, in the words both products store; `full` is the default */
  enum class Level(val id: String) {
    OFF("off"), LITE("lite"), FULL("full"), ULTRA("ultra");

    companion object {
      val DEFAULT = FULL

      /** An unknown or empty word is the default: a typo in a setting must not silently switch the style off */
      fun of(id: String?): Level = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: DEFAULT
    }
  }

  private const val LEVEL_PREFIX = "level:"
  private const val OFF_SECTION = "off"
  private val SECTION = Regex("^## ", RegexOption.MULTILINE)

  /** One `## ` section of the file: its heading and its whole text, heading included */
  private data class Section(val heading: String, val text: String)

  /** The sections after the leading comment; the comment is for the people editing the file, not for the model */
  private fun sections(file: String): List<Section> {
    val body = file.trimStart().let { if (it.startsWith("<!--")) it.substringAfter("-->", "") else it }
    return body.split(SECTION).map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
      Section(part.substringBefore('\n').trim().lowercase(), "## $part")
    }
  }

  private fun levelOf(heading: String): String? =
    heading.takeIf { it.startsWith(LEVEL_PREFIX) }?.removePrefix(LEVEL_PREFIX)?.trim()

  /**
   * The instruction for [level]: every common section, and of the level sections only the chosen one, in file order
   * Empty at [Level.OFF], and empty when the file has no section for the level: half an instruction is worse than none
   */
  fun instruction(file: String, level: Level): String {
    if (level == Level.OFF) return ""
    val all = sections(file)
    if (all.none { levelOf(it.heading) == level.id }) return ""
    return all.filter { section ->
      val sectionLevel = levelOf(section.heading)
      if (sectionLevel != null) sectionLevel == level.id else section.heading != OFF_SECTION
    }.joinToString("\n\n") { it.text }
  }

  /** What to tell an agent that was given the style and now has it switched off */
  fun offNotice(file: String): String = sections(file).firstOrNull { it.heading == OFF_SECTION }?.text.orEmpty()

  /**
   * What an agent session must receive before this prompt, given the level it was last given ([sent], null — nothing yet)
   *
   * An agent keeps its own history, so the instruction goes once and again only when the level changes:
   * Repeated with every prompt it would add its whole length to the history on each turn and eat the saving
   * Null — nothing to send
   */
  fun forAgent(file: String, sent: Level?, current: Level): String? = when {
    current == sent -> null
    current == Level.OFF -> if (sent == null) null else offNotice(file).ifEmpty { null }
    else -> instruction(file, current).ifEmpty { null }
  }
}
