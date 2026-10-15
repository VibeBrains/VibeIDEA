// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ApiKeyResolver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure logic of the Models settings page: who waits for a key, what the header says, where a list came from
 *
 * No Swing and no I/O in here, so every rule is a unit test and the view only draws the answers
 */
object ModelsPage {
  /** How the page sees one provider after asking where its key could be */
  enum class KeyVerdict {
    /** A key was found, or none is needed: a local server, `"auth": "none"` */
    USABLE,

    /** The quiet lookup found nothing, but the keychain holds an entry (or could not be asked): the key is there, unread */
    NOT_READ,

    /** No key anywhere: the provider cannot be used until one is entered */
    WAITING,
  }

  /**
   * The verdict from the same test the page always used for its old «no key» line, refined by the keychain
   *
   * [quietMissing] is `resolved == null || resolved.missingKey` of a QUIET resolve, which never reads the keychain
   * That alone is not enough for a verdict: a key that sits in the keychain and has not been read in this run looks
   * exactly like a missing one, and a page that sends such a provider to the waiting block tells a person to re-enter a key
   * that is fine
   *
   * So the keychain is asked, through [presence], only when the quiet lookup found nothing: WAITING needs an ABSENT answer,
   * and "could not ask" is no more a "no" here than it is on the Providers page
   */
  fun verdict(quietMissing: Boolean, presence: () -> ApiKeyResolver.Presence): KeyVerdict = when {
    !quietMissing -> KeyVerdict.USABLE
    presence() == ApiKeyResolver.Presence.ABSENT -> KeyVerdict.WAITING
    else -> KeyVerdict.NOT_READ
  }

  /** A provider as the grouping sees it */
  data class Assessed(val id: String, val name: String, val verdict: KeyVerdict)

  /** The two groups of the page, each in the order of the registry */
  data class Grouping(val keyed: List<Assessed>, val waiting: List<Assessed>)

  fun group(providers: List<Assessed>): Grouping {
    val (waiting, keyed) = providers.partition { it.verdict == KeyVerdict.WAITING }
    return Grouping(keyed, waiting)
  }

  /** Where the list of a provider's models came from, as the header of its block says it */
  sealed interface Source {
    /** The catalog has been asked for and has not answered yet */
    object Asking : Source

    /** The provider's API answered (or the cache holds an answer); [atMs] is zero when the time is unknown, [added] counts new models */
    data class Catalog(val count: Int, val atMs: Long, val added: Int = 0) : Source

    /** The API answered with nothing: the models are the ones the file declares */
    object EmptyCatalog : Source

    /** `fetch: false` in the provider file: the catalog is never asked for */
    object File : Source

    /** The request failed: the models shown are whatever was known before */
    data class Failed(val reason: String) : Source

    /** The key is in the keychain and has not been read in this run: only a person's own request reads it */
    object KeyNotRead : Source
  }

  fun sourceText(source: Source, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String = when (source) {
    Source.Asking -> t("settings.models.asking")
    Source.File -> t("settings.models.fetchDisabled")
    Source.EmptyCatalog -> t("settings.models.catalogEmpty")
    Source.KeyNotRead -> t("settings.models.keyNotRead")
    is Source.Failed -> t("settings.models.fetchFailed", "reason" to source.reason)
    is Source.Catalog -> catalogText(source, nowMs, zone)
  }

  private fun catalogText(source: Source.Catalog, nowMs: Long, zone: ZoneId): String {
    val word = modelsWord(source.count)
    // A zero stamp is a cache record that never learned its time: printing 03:00 of 1970 would be an invented number
    val line = if (source.atMs > 0) {
      t("settings.models.catalog", "count" to source.count, "word" to word, "time" to stamp(source.atMs, nowMs, zone))
    }
    else t("settings.models.catalogUndated", "count" to source.count, "word" to word)
    return if (source.added > 0) line + t("settings.models.added", "count" to source.added) else line
  }

  /**
   * What the header of a block with a key says before any answer has come
   *
   * The cached catalog stands in for the answer when there is one, unless the page knows something that matters more:
   * the catalog is never asked for, or the key is there but cannot be used until someone reads it
   */
  fun initialSource(fetchEnabled: Boolean, verdict: KeyVerdict, cached: Source.Catalog?): Source = when {
    !fetchEnabled -> Source.File
    verdict == KeyVerdict.NOT_READ -> Source.KeyNotRead
    else -> cached ?: Source.Asking
  }

  private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
  private val DATE_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

  /** The time of a catalog: the clock alone for today, with the date for any other day, so that yesterday's list is not read as fresh */
  fun stamp(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (if (at.toLocalDate() == today) CLOCK else DATE_CLOCK).format(at)
  }

  /** The line next to the title: providers that can be used and models that are switched on in them */
  fun summary(providers: Int, enabledModels: Int): String = t(
    "settings.models.summary",
    "providers" to providers, "providerWord" to providersWord(providers),
    "models" to enabledModels, "modelWord" to modelsWord(enabledModels), "enabledWord" to enabledWord(enabledModels),
  )

  /** The first [limit] names, with an ellipsis when there are more; the title of the waiting block shows them */
  fun leadNames(names: List<String>, limit: Int = NAMES_IN_TITLE): String =
    names.take(limit).joinToString(", ") + if (names.size > limit) ELLIPSIS else ""

  /** How many provider names the title of the block of waiting providers lists */
  const val NAMES_IN_TITLE = 3

  private const val ELLIPSIS = "…"

  private enum class Plural { ONE, FEW, MANY }

  private fun plural(n: Int): Plural = when {
    n % 10 == 1 && n % 100 != 11 -> Plural.ONE
    n % 10 in 2..4 && n % 100 !in 12..14 -> Plural.FEW
    else -> Plural.MANY
  }

  // Keys are spelled out in every branch: a key built from parts cannot be found by the localisation gate
  fun modelsWord(n: Int): String = when (plural(n)) {
    Plural.ONE -> t("settings.models.word.one")
    Plural.FEW -> t("settings.models.word.few")
    Plural.MANY -> t("settings.models.word.many")
  }

  fun providersWord(n: Int): String = when (plural(n)) {
    Plural.ONE -> t("settings.models.providerWord.one")
    Plural.FEW -> t("settings.models.providerWord.few")
    Plural.MANY -> t("settings.models.providerWord.many")
  }

  /** The participle of "enabled" agrees with the number it follows, like the noun does */
  fun enabledWord(n: Int): String = when (plural(n)) {
    Plural.ONE -> t("settings.models.enabledWord.one")
    Plural.FEW -> t("settings.models.enabledWord.few")
    Plural.MANY -> t("settings.models.enabledWord.many")
  }
}
