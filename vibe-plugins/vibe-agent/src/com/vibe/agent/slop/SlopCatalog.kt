// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** How much a finding weighs, weakest first; the catalogue's scoring gives each level its points. */
enum class SlopSeverity(val id: String) {
  NOTE("note"), MINOR("minor"), MAJOR("major"), BLOCKER("blocker");

  companion object {
    fun of(id: String?): SlopSeverity? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
  }
}

/** What a rule matches with. The last six are checks whose logic is code; the catalogue names them and prices them. */
enum class SlopKind(val id: String) {
  WORDS("words"), PHRASES("phrases"), OPENER("opener"), REGEX("regex"), DENSITY("density"),
  INVISIBLE("invisible"), RHYTHM_UNIFORM("rhythm-uniform"), RHYTHM_FRAGMENTS("rhythm-fragments"),
  DECORATIVE_BOLD("decorative-bold"), HEADING_STUB("heading-stub"), TITLE_CASE("title-case");

  companion object {
    fun of(id: String?): SlopKind? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
  }
}

/**
 * Which paragraphs a rule reads, by their script.
 *
 * A Russian list has nothing to find in English and the other way round, and a rule of one language's punctuation is
 * wrong in the other: the em dash is a tell in English prose and plain grammar in Russian.
 */
enum class SlopLang(val id: String) {
  RU("ru"), EN("en"), ANY("any");

  companion object {
    fun of(id: String?): SlopLang? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
  }
}

data class SlopRule(
  val id: String,
  val lang: SlopLang,
  val name: String,
  val severity: SlopSeverity,
  val kind: SlopKind,
  val fix: String,
  val items: List<String> = emptyList(),
  val patterns: List<String> = emptyList(),
  val caseSensitive: Boolean = false,
  /** Density rules: fewer occurrences than this are ordinary language, however dense. */
  val minCount: Int = DEFAULT_MIN_COUNT,
  /** Density rules: hits per thousand words from which the device reads as a habit. */
  val thresholdPer1000: Double = 0.0,
) {
  companion object {
    const val DEFAULT_MIN_COUNT = 3
  }
}

/**
 * The fixed arithmetic: the same findings always give the same score.
 *
 * A text starts at [start]; the first finding of a rule costs its severity's points, every repeat of it the repeat
 * points, and no rule takes more than [ruleCapMultiplier] times its first cost — so one habit cannot sink a text on
 * its own. A text passes at [passScore] with nothing above [maxSeverity].
 */
data class SlopScoring(
  val start: Double,
  val floor: Double,
  val severityPoints: Map<SlopSeverity, Double>,
  val repeatPoints: Map<SlopSeverity, Double>,
  val ruleCapMultiplier: Double,
  val passScore: Double,
  val maxSeverity: SlopSeverity,
)

/**
 * The catalogue of AI-writing tells: what to look for in prose, what each costs, and when a text passes.
 *
 * Data, not code. The lists live in the shared `.vibe` set (`slop/catalog.jsonc`) and are read from the build, never
 * seeded into a project: a seeded copy would freeze today's lists the way a seeded base-language file freezes today's
 * wording. A project narrows or extends the catalogue with `.vibe/slop.json` ([SlopOverrides]).
 *
 * Pure: text in, a catalogue out. A broken rule is dropped with a warning and the rest keep working.
 */
data class SlopCatalog(val version: Int, val scoring: SlopScoring, val rules: List<SlopRule>) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, onWarning: (String) -> Unit): SlopCatalog? {
      val root = runCatching { json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text)) as? JsonObject }
        .getOrElse { onWarning("catalog: ${it.message}"); null } ?: return null
      val scoring = scoringOf(root["scoring"] as? JsonObject, onWarning) ?: return null
      val rules = rulesOf(root["rules"], "catalog", onWarning)
      return SlopCatalog((root["version"] as? JsonPrimitive)?.intOrNull ?: 1, scoring, rules)
    }

    /** Rules from a JSON array — the catalogue's and a project's own share one reader, so they cannot drift apart. */
    internal fun rulesOf(element: JsonElement?, where: String, onWarning: (String) -> Unit): List<SlopRule> {
      val seen = HashSet<String>()
      return (element as? JsonArray).orEmpty().mapNotNull { entry ->
        val rule = ruleOf(entry as? JsonObject, where, onWarning) ?: return@mapNotNull null
        if (!seen.add(rule.id.uppercase())) {
          onWarning("$where: rule ${rule.id} is declared twice; the first one is used")
          return@mapNotNull null
        }
        rule
      }
    }

    private fun ruleOf(o: JsonObject?, where: String, onWarning: (String) -> Unit): SlopRule? {
      if (o == null) return null
      val id = string(o["id"])?.takeIf { it.isNotBlank() } ?: run { onWarning("$where: a rule without an id"); return null }
      val kind = SlopKind.of(string(o["kind"])) ?: run { onWarning("$where: $id has an unknown kind"); return null }
      val severity = SlopSeverity.of(string(o["severity"])) ?: run { onWarning("$where: $id has an unknown severity"); return null }
      val lang = SlopLang.of(string(o["lang"]) ?: SlopLang.ANY.id) ?: run { onWarning("$where: $id has an unknown lang"); return null }
      val rule = SlopRule(
        id = id,
        lang = lang,
        name = string(o["name"]) ?: id,
        severity = severity,
        kind = kind,
        fix = string(o["fix"]).orEmpty(),
        items = strings(o["items"]),
        patterns = strings(o["patterns"]),
        caseSensitive = (o["caseSensitive"] as? JsonPrimitive)?.booleanOrNull ?: false,
        minCount = (o["minCount"] as? JsonPrimitive)?.intOrNull ?: SlopRule.DEFAULT_MIN_COUNT,
        thresholdPer1000 = (o["thresholdPer1000"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
      )
      val empty = when (kind) {
        SlopKind.WORDS, SlopKind.PHRASES, SlopKind.OPENER -> rule.items.isEmpty() && rule.patterns.isEmpty()
        SlopKind.REGEX, SlopKind.DENSITY -> rule.patterns.isEmpty()
        else -> false
      }
      if (empty) {
        onWarning("$where: $id has nothing to match")
        return null
      }
      return rule
    }

    private fun scoringOf(o: JsonObject?, onWarning: (String) -> Unit): SlopScoring? {
      if (o == null) {
        onWarning("catalog: no scoring")
        return null
      }
      fun points(key: String): Map<SlopSeverity, Double>? {
        val table = o[key] as? JsonObject ?: return null
        val out = SlopSeverity.entries.associateWith { (table[it.id] as? JsonPrimitive)?.doubleOrNull }
        return if (out.values.any { it == null }) null else out.mapValues { it.value!! }
      }
      val severityPoints = points("severityPoints") ?: run { onWarning("catalog: severityPoints must price every severity"); return null }
      val repeatPoints = points("repeatPoints") ?: run { onWarning("catalog: repeatPoints must price every severity"); return null }
      return SlopScoring(
        start = number(o["start"]) ?: 100.0,
        floor = number(o["floor"]) ?: 0.0,
        severityPoints = severityPoints,
        repeatPoints = repeatPoints,
        ruleCapMultiplier = number(o["ruleCapMultiplier"]) ?: 3.0,
        passScore = number(o["passScore"]) ?: 90.0,
        maxSeverity = SlopSeverity.of(string(o["maxSeverity"])) ?: SlopSeverity.MINOR,
      )
    }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull

    private fun strings(e: JsonElement?): List<String> =
      (e as? JsonArray).orEmpty().mapNotNull { string(it) }.filter { it.isNotBlank() }
  }
}

/** A rule with its patterns compiled once; the catalogue is matched against every text an agent writes. */
class CompiledRule(val rule: SlopRule, val patterns: List<Pattern>)

/**
 * The catalogue ready to match: patterns compiled, a project's allow-list compiled, broken patterns dropped.
 *
 * Built once per catalogue text; a project's overrides make a new one ([SlopOverrides.applyTo]).
 */
class CompiledCatalog(
  val scoring: SlopScoring,
  val rules: List<CompiledRule>,
  /** A finding whose matched text is entirely one of these is the project's real term, not a tell. */
  val allow: List<Pattern> = emptyList(),
) {
  /**
   * Only the list and template rules — for a short fragment such as a headline or a button, where rhythm, density and
   * layout say nothing.
   */
  fun lexical(): CompiledCatalog =
    CompiledCatalog(scoring, rules.filter { it.rule.kind in LEXICAL }, allow)

  companion object {
    private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS or Pattern.MULTILINE

    /** A letter, digit, underscore or hyphen: what a listed word must not be glued to on either side. */
    private const val WORD_EDGE = "[\\p{L}\\p{N}_-]"

    fun of(catalog: SlopCatalog, onWarning: (String) -> Unit): CompiledCatalog =
      CompiledCatalog(catalog.scoring, catalog.rules.mapNotNull { compile(it, onWarning) })

    fun compile(rule: SlopRule, onWarning: (String) -> Unit): CompiledRule? {
      val flags = if (rule.caseSensitive) Pattern.UNICODE_CHARACTER_CLASS or Pattern.MULTILINE else FLAGS
      val sources = when (rule.kind) {
        SlopKind.WORDS, SlopKind.PHRASES -> listOfNotNull(listPattern(rule.items)) + rule.patterns
        // An opener is matched at the start of a sentence by [TextSlop]; its pattern only has to recognise the words.
        SlopKind.OPENER -> listOfNotNull(openerPattern(rule.items)) + rule.patterns
        else -> rule.patterns
      }
      val compiled = sources.mapNotNull { source ->
        try {
          Pattern.compile(source, flags)
        }
        catch (e: PatternSyntaxException) {
          onWarning("${rule.id}: pattern does not compile: ${e.description}")
          null
        }
      }
      if (compiled.isEmpty() && sources.isNotEmpty()) return null
      return CompiledRule(rule, compiled)
    }

    /** Listed words and phrases as one alternation, longest first so a phrase wins over a word it contains. */
    internal fun listPattern(items: List<String>): String? {
      if (items.isEmpty()) return null
      val body = items.sortedByDescending { it.length }.joinToString("|") { item(it) }
      return "(?<!$WORD_EDGE)(?:$body)(?!$WORD_EDGE)"
    }

    /** Words that open a sentence, followed by a comma, a colon or just a space. */
    private fun openerPattern(items: List<String>): String? {
      if (items.isEmpty()) return null
      val body = items.sortedByDescending { it.length }.joinToString("|") { item(it) }
      return "^(?:$body)(?=\\s*[,:]?\\s)"
    }

    /** Patterns a project allows; the same notation as the lists, so an allowed stem covers every form of the word. */
    fun allowPatterns(items: List<String>): List<Pattern> =
      items.filter { it.isNotBlank() }.map { Pattern.compile(item(it.trim()), FLAGS) }

    /**
     * One list item as a pattern: literal text, any run of spaces for a space, either apostrophe, the Russian yo
     * (U+0451) also matching the plain ye (U+0435) people type instead, and a trailing `*` on a word for any ending —
     * Russian inflects, and a list of every form of every word would be a list nobody maintains.
     */
    internal fun item(text: String): String = text.trim().split(Regex("\\s+")).joinToString("\\s+") { token ->
      val stem = token.endsWith("*") && token.length > 1
      val body = (if (stem) token.dropLast(1) else token).map { c ->
        when (c) {
          'ё' -> "[её]"
          'Ё' -> "[ЕЁ]"
          '\'', '’' -> "['’]"
          in REGEX_META -> "\\$c"
          else -> c.toString()
        }
      }.joinToString("")
      if (stem) "$body\\p{L}*" else body
    }

    private const val REGEX_META = "\\.[]{}()*+?^$|"

    private val LEXICAL = setOf(SlopKind.WORDS, SlopKind.PHRASES, SlopKind.OPENER, SlopKind.REGEX)
  }
}
