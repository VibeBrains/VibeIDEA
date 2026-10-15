// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license
package com.vibe.agent.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What a journal line is about, in the order the filter lists it
 *
 * The ids are stored, so they never change; the names are for the code
 * Same seven categories as the journal of the sibling product, which keeps the two chats alike
 */
enum class JournalCategory(val id: String) {
  PROVIDERS("providers"),
  KEYS("keys"),
  HOOKS("hooks"),
  CONFIG("config"),
  AGENTS("agents"),
  CHECKS("checks"),

  /** Not `OTHER`: the chat file already has `Role.OTHER`, which means tool cards and not the journal */
  MISC("other");

  companion object {
    fun ofId(id: String?): JournalCategory? = entries.firstOrNull { it.id == id }
  }
}

enum class JournalLevel(val id: String) {
  INFO("info"),

  /** Needs a person to act, or says that a feature does not work */
  WARNING("warning");

  companion object {
    fun ofId(id: String?): JournalLevel? = entries.firstOrNull { it.id == id }
  }
}

/**
 * The settings page a line points to; the entry offers a button that opens it
 *
 * The page is an id and not a class so that the model stays free of the platform and the entry stays storable
 * The panel maps an id to its configurable class
 */
enum class SettingsPage(val id: String) {
  PROVIDERS("providers"),
  MODELS("models"),
  AGENT("agent"),
  CHAT("chat"),
  INTERFACE("interface"),
  APPEARANCE("appearance");

  companion object {
    fun ofId(id: String?): SettingsPage? = entries.firstOrNull { it.id == id }
  }
}

/** One line as its source states it */
data class JournalInput(
  val category: JournalCategory,
  val level: JournalLevel,
  val text: String,
  val page: SettingsPage? = null,
  /**
   * The line is background information that the person did not ask for, and it enters the journal already read
   *
   * The greeting that every start of the IDE writes is the case: the badge counts what is news, and a start is not news
   */
  val read: Boolean = false,
)

data class JournalEntry(
  /** Grows with every new entry; a repeat keeps the id of the entry it joined */
  val id: Long,
  /** Epoch millis of the last occurrence */
  val at: Long,
  /** How many times in a row the same line happened, 1 or more */
  val count: Int,
  val read: Boolean,
  val category: JournalCategory,
  val level: JournalLevel,
  val text: String,
  val page: SettingsPage?,
) {
  fun isSameLine(input: JournalInput): Boolean =
    category == input.category && level == input.level && text == input.text && page == input.page
}

data class JournalFilter(
  /** Null: every category */
  val category: JournalCategory? = null,
  val onlyWarnings: Boolean = false,
)

data class JournalSummary(val total: Int, val unread: Int, val hasUnreadWarning: Boolean)

data class JournalPreview(val text: String, val truncated: Boolean)

data class JournalCategoryCount(val category: JournalCategory, val count: Int)

/**
 * The pure model of the chat journal: the service lines of the IDE that are not part of the conversation
 *
 * No Swing and no platform here, so that the service, the panel and the tests all share these functions
 *
 * The journal is a ring buffer, oldest entry first
 * Two lines in a row that say the same thing are one entry with a counter
 * Unread is a flag on the entry, so the badge counts entries and not occurrences
 * Every function returns a new list, or the same list when there is nothing to change, so an observer can compare by reference
 */
object ChatJournal {
  /** An entry keeps at most this much text: the log of a failed build must not turn the stored journal into megabytes */
  const val MAX_TEXT_CHARS = 4000

  /** The list shows this many lines of a long entry until the reader asks for the rest */
  const val PREVIEW_LINES = 4

  /** About four wrapped rows at the width of the panel; a long line without breaks is cut by this */
  const val PREVIEW_CHARS = 200

  /** The text without blank edges, cut to the limit with an ellipsis */
  fun clip(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.length > MAX_TEXT_CHARS) trimmed.take(MAX_TEXT_CHARS - 1) + ELLIPSIS else trimmed
  }

  /**
   * [entries] plus one more line
   *
   * A line equal to the newest entry joins it: the counter grows, the time moves to [now], and the entry is unread again
   * unless both the entry and the repeat are read: a repeat of information does not turn it into news
   * The oldest entries leave when the buffer is over [maxEntries]
   * A line without text is not an entry
   */
  fun append(entries: List<JournalEntry>, input: JournalInput, now: Long, maxEntries: Int): List<JournalEntry> {
    val line = input.copy(text = clip(input.text))
    if (line.text.isEmpty()) return entries
    val last = entries.lastOrNull()
    if (last != null && last.isSameLine(line)) {
      return entries.dropLast(1) + last.copy(count = last.count + 1, at = now, read = last.read && line.read)
    }
    val entry = JournalEntry((last?.id ?: 0L) + 1, now, 1, line.read, line.category, line.level, line.text, line.page)
    return trim(entries + entry, maxEntries)
  }

  /**
   * True when [entries] already hold a line of [category] with this [text]: the person has been told, so a repeat is not news
   * The line is compared as the journal keeps it, clipped
   */
  fun hasLine(entries: List<JournalEntry>, category: JournalCategory, text: String): Boolean {
    val kept = clip(text)
    return entries.any { it.category == category && it.text == kept }
  }

  /** The buffer cut to a limit; the same list when nothing has to go */
  fun trim(entries: List<JournalEntry>, maxEntries: Int): List<JournalEntry> =
    if (entries.size > maxEntries) entries.takeLast(maxEntries) else entries

  /** Every entry read; the same list when it already is, so an observer is not woken for nothing */
  fun markAllRead(entries: List<JournalEntry>): List<JournalEntry> =
    if (entries.any { !it.read }) entries.map { if (it.read) it else it.copy(read = true) } else entries

  fun summarize(entries: List<JournalEntry>): JournalSummary {
    val unread = entries.filter { !it.read }
    return JournalSummary(entries.size, unread.size, unread.any { it.level == JournalLevel.WARNING })
  }

  /** The entries that pass the filter, the newest first */
  fun filter(entries: List<JournalEntry>, filter: JournalFilter): List<JournalEntry> =
    entries.asReversed().filter { entry ->
      (!filter.onlyWarnings || entry.level == JournalLevel.WARNING) && (filter.category == null || entry.category == filter.category)
    }

  /** How many entries each category has, in the order of the enum; a category without entries is left out */
  fun countByCategory(entries: List<JournalEntry>): List<JournalCategoryCount> {
    val counts = entries.groupingBy { it.category }.eachCount()
    return JournalCategory.entries.filter { it in counts }.map { JournalCategoryCount(it, counts.getValue(it)) }
  }

  /** The head of a long text for the list; `truncated` tells the panel to offer the rest */
  fun preview(text: String): JournalPreview {
    val lines = text.split('\n')
    val head = lines.take(PREVIEW_LINES).joinToString("\n")
    if (lines.size <= PREVIEW_LINES && head.length <= PREVIEW_CHARS) return JournalPreview(text, false)
    val cut = if (head.length > PREVIEW_CHARS) head.take(PREVIEW_CHARS - 1) else head
    return JournalPreview(cut + ELLIPSIS, true)
  }

  fun serialize(entries: List<JournalEntry>): String =
    JsonArray(entries.map { entry ->
      buildJsonObject {
        put(KEY_ID, entry.id)
        put(KEY_AT, entry.at)
        put(KEY_COUNT, entry.count)
        put(KEY_READ, entry.read)
        put(KEY_CATEGORY, entry.category.id)
        put(KEY_LEVEL, entry.level.id)
        put(KEY_TEXT, entry.text)
        entry.page?.let { put(KEY_PAGE, it.id) }
      }
    }).toString()

  /**
   * The buffer read back from storage
   *
   * A damaged text or a damaged entry costs only itself: the journal is a convenience and must never stop the project
   */
  fun parse(raw: String?, maxEntries: Int): List<JournalEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    val parsed = try {
      Json.parseToJsonElement(raw)
    }
    catch (e: Exception) {
      return emptyList()
    }
    val array = parsed as? JsonArray ?: return emptyList()
    return trim(array.mapNotNull { parseEntry(it) }, maxEntries)
  }

  private fun parseEntry(element: JsonElement): JournalEntry? {
    val obj = element as? JsonObject ?: return null
    val id = obj.long(KEY_ID) ?: return null
    val at = obj.long(KEY_AT) ?: return null
    val count = obj.long(KEY_COUNT)?.takeIf { it >= 1 } ?: return null
    val read = (obj[KEY_READ] as? JsonPrimitive)?.booleanOrNull ?: return null
    val text = obj.string(KEY_TEXT) ?: return null
    val category = JournalCategory.ofId(obj.string(KEY_CATEGORY)) ?: return null
    val level = JournalLevel.ofId(obj.string(KEY_LEVEL)) ?: return null
    return JournalEntry(id, at, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), read, category, level, clip(text), SettingsPage.ofId(obj.string(KEY_PAGE)))
  }

  private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  private const val ELLIPSIS = "…"
  private const val KEY_ID = "id"
  private const val KEY_AT = "at"
  private const val KEY_COUNT = "count"
  private const val KEY_READ = "read"
  private const val KEY_CATEGORY = "category"
  private const val KEY_LEVEL = "level"
  private const val KEY_TEXT = "text"
  private const val KEY_PAGE = "page"
}
