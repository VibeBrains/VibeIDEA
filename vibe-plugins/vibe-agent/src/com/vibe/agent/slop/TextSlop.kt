// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import java.util.regex.Pattern

/**
 * One tell in a text: which rule, where, what it matched and how to fix it. Lines and columns start at 1.
 *
 * A habit rule points at its first occurrence and carries the rest as [density]: its problem is the count, not any one
 * sentence.
 */
data class SlopFinding(
  val rule: String,
  val name: String,
  val severity: SlopSeverity,
  val line: Int,
  val column: Int,
  val start: Int,
  val end: Int,
  val match: String,
  val fix: String,
  val density: SlopDensity? = null,
)

/** How often a habit occurs: [count] times, [perThousand] per thousand words, on [lines] (the first few, from 1). */
data class SlopDensity(val count: Int, val perThousand: Double, val lines: List<Int>)

/** What one rule cost the text, with the count that produced the cost. */
data class SlopDeduction(val rule: String, val name: String, val severity: SlopSeverity, val count: Int, val points: Double)

data class SlopReport(
  val score: Double,
  val passed: Boolean,
  val passScore: Double,
  val maxSeverity: SlopSeverity,
  /** Rules whose findings are above [maxSeverity]: any one of them fails the text whatever the score. */
  val blocking: List<String>,
  val words: Int,
  val findings: List<SlopFinding>,
  val deductions: List<SlopDeduction>,
)

/**
 * The deterministic detector of AI-writing tells in prose — the part of a check that cannot be argued with.
 *
 * Why rules and not a model asked «does this read as AI»: a model answers differently on the same text twice, cannot
 * point at the line, and grades its own writing kindly. Rules read what is on the page, the same way every time, and
 * every finding carries its line and the text it matched. What rules cannot see — an invented fact, a meaning that
 * shifted while smoothing — is the reviewer's job (the `anti-slop` skill); the score is a floor, not a verdict.
 *
 * The method follows `misbahsy/anti-ai-slop` (MIT): masked non-prose, word lists and sentence templates, rates for
 * devices that are ordinary once and a tic three times, rhythm, formatting, and fixed arithmetic. The Russian half of
 * the catalogue is ours, and so is matching an opener only where a sentence starts.
 *
 * Pure: text and a compiled catalogue in, a report out.
 */
object TextSlop {
  /** A Windows line break, or a lone carriage return. */
  private val LINE_BREAK = Regex("\r\n?")

  fun analyze(source: String, catalog: CompiledCatalog): SlopReport {
    // Every pattern here is written for \n, the frontmatter one first of all, and a file read as it lies on disk with
    // Windows line breaks had its frontmatter read as prose. Normalising at the entry gives every surface the same
    // reading at once; a line keeps its number, and a column its place.
    val text = LINE_BREAK.replace(source, "\n")
    val masked = mask(text)
    val lineStarts = lineStarts(text)
    val suppress = suppressed(text.split('\n'))
    val paragraphs = paragraphs(masked)
    val sentences = sentences(masked)
    val context = Context(text, masked, lineStarts, suppress, paragraphs)

    val raw = ArrayList<SlopFinding>()
    for (compiled in catalog.rules) {
      val rule = compiled.rule
      when (rule.kind) {
        SlopKind.WORDS, SlopKind.PHRASES, SlopKind.REGEX -> raw += lexical(compiled, context)
        SlopKind.OPENER -> raw += openers(compiled, context, sentences)
        SlopKind.DENSITY -> density(compiled, context)?.let { raw += it }
        SlopKind.INVISIBLE -> raw += invisible(rule, context)
        SlopKind.RHYTHM_UNIFORM -> raw += uniformRhythm(rule, context, sentences)
        SlopKind.RHYTHM_FRAGMENTS -> raw += stackedFragments(rule, context, sentences)
        SlopKind.DECORATIVE_BOLD -> raw += decorativeBold(rule, context)
        SlopKind.HEADING_STUB -> raw += headingStubs(rule, context)
        SlopKind.TITLE_CASE -> raw += titleCase(rule, context)
      }
    }
    val findings = dedupe(raw.filterNot { allowed(it, catalog.allow) })
      .sortedWith(compareBy({ it.line }, { it.column }, { it.rule }))
    return score(findings, catalog.scoring, words(masked))
  }

  // --- masking: never flag what the writer did not write as prose ---

  private val MASKS: List<Pattern> = listOf(
    Pattern.compile("^```.*?^```", Pattern.MULTILINE or Pattern.DOTALL),
    Pattern.compile("^~~~.*?^~~~", Pattern.MULTILINE or Pattern.DOTALL),
    Pattern.compile("<!--.*?-->", Pattern.DOTALL),
    Pattern.compile("`[^`\\n]+`"),
    Pattern.compile("^(?:\\t| {4,})\\S.*$", Pattern.MULTILINE),
    Pattern.compile("^\\s*>.*$", Pattern.MULTILINE),
    Pattern.compile("\\]\\([^)\\s]+\\)"),
    Pattern.compile("https?://\\S+"),
    // Quoted material is someone else's words, a UI label or an example — including Russian quotes, which the source
    // this follows did not have.
    Pattern.compile("\"(?:[^\"\\n]|\\n(?!\\s*\\n)){1,400}\""),
    Pattern.compile("“(?:[^”\\n]|\\n(?!\\s*\\n)){1,400}”"),
    Pattern.compile("«(?:[^»\\n]|\\n(?!\\s*\\n)){1,400}»"),
    Pattern.compile("„(?:[^“\\n]|\\n(?!\\s*\\n)){1,400}“"),
  )

  private val FRONTMATTER = Pattern.compile("\\A---\\n.*?\\n---\\n", Pattern.DOTALL)

  /** Non-prose replaced by spaces, newlines and offsets kept, so every position still points into the original. */
  internal fun mask(text: String): String {
    val chars = text.toCharArray()
    fun blank(start: Int, end: Int) {
      for (i in start until minOf(end, chars.size)) if (chars[i] != '\n') chars[i] = ' '
    }
    FRONTMATTER.matcher(text).let { if (it.find()) blank(it.start(), it.end()) }
    for (pattern in MASKS) {
      val m = pattern.matcher(text)
      while (m.find()) blank(m.start(), m.end())
    }
    return String(chars)
  }

  // --- suppression ---

  /** `<!-- slop-ignore ID[, ID…] [— reason] -->`; `ALL` suppresses every rule. */
  private val IGNORE = Pattern.compile("<!--\\s*slop-ignore\\s+([A-Za-z0-9_, -]+?)(?:\\s+[—–]+\\s+.*?|\\s+--\\s+.*?)?\\s*-->")

  /**
   * Line → rule ids suppressed there. A directive at the end of a line covers it and the next one; a directive alone on
   * its line covers the paragraph below — the sentence being excused usually wraps.
   */
  internal fun suppressed(lines: List<String>): Map<Int, Set<String>> {
    val out = HashMap<Int, MutableSet<String>>()
    for ((i, line) in lines.withIndex()) {
      val m = IGNORE.matcher(line)
      if (!m.find()) continue
      val ids = m.group(1).split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
      out.getOrPut(i) { HashSet() } += ids
      if (m.replaceAll("").isNotBlank()) {
        out.getOrPut(i + 1) { HashSet() } += ids
        continue
      }
      for (j in i + 1 until lines.size) {
        if (lines[j].isBlank()) break
        out.getOrPut(j) { HashSet() } += ids
      }
    }
    return out
  }

  // --- positions, paragraphs and sentences ---

  private class Paragraph(val start: Int, val end: Int, val lang: SlopLang)

  private class Sentence(val start: Int, val end: Int, val text: String, val paragraph: Paragraph?)

  private class Context(
    val text: String,
    val masked: String,
    val lineStarts: IntArray,
    val suppress: Map<Int, Set<String>>,
    val paragraphs: List<Paragraph>,
  ) {
    /** Zero-based line of an offset. */
    fun lineOf(offset: Int): Int {
      var lo = 0
      var hi = lineStarts.size - 1
      while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
      }
      return lo
    }

    fun suppressed(rule: String, offset: Int): Boolean {
      val ids = suppress[lineOf(offset)] ?: return false
      return rule.uppercase() in ids || "ALL" in ids
    }

    fun langAt(offset: Int): SlopLang? = paragraphs.firstOrNull { offset >= it.start && offset < it.end }?.lang

    fun reads(rule: SlopRule, offset: Int): Boolean = rule.lang == SlopLang.ANY || langAt(offset) == rule.lang

    fun finding(rule: SlopRule, start: Int, end: Int, match: String = text.substring(start, end)): SlopFinding {
      val line = lineOf(start)
      return SlopFinding(rule.id, rule.name, rule.severity, line + 1, start - lineStarts[line] + 1, start, end,
                         match.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ").take(MATCH_CHARS), rule.fix)
    }
  }

  private fun lineStarts(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts.add(0)
    for ((i, c) in text.withIndex()) if (c == '\n') starts.add(i + 1)
    return starts.toIntArray()
  }

  private val BLANK_LINE = Regex("\\n\\s*\\n")
  private val TOKEN = Regex("[\\p{L}\\p{N}_./\\\\'’-]+")

  /**
   * Blocks between blank lines, each with its language.
   *
   * Counted in ordinary words, not letters: a Russian line is full of Latin names — a heading with the product's name,
   * `## TypeScript — vtsls` — and by letters it read as English, so the English rule on the em dash fired on Russian
   * grammar. Names, acronyms and identifiers say nothing about the language of the sentence around them, and a block
   * with too few ordinary words takes the language of the document.
   */
  private fun paragraphs(masked: String): List<Paragraph> {
    val blocks = ArrayList<Pair<Int, Int>>()
    var from = 0
    for (gap in BLANK_LINE.findAll(masked)) {
      if (from < gap.range.first) blocks.add(from to gap.range.first)
      from = gap.range.last + 1
    }
    if (from < masked.length) blocks.add(from to masked.length)
    val counts = blocks.map { (start, end) -> scriptCounts(masked.substring(start, end)) }
    val document = languageOf(counts.sumOf { it.first }, counts.sumOf { it.second })
    return blocks.zip(counts).mapNotNull { (range, count) ->
      val (ru, en) = count
      val lang = if (ru + en >= LANGUAGE_EVIDENCE) languageOf(ru, en) else document
      lang?.let { Paragraph(range.first, range.second, it) }
    }
  }

  private fun languageOf(ru: Int, en: Int): SlopLang? = when {
    ru == 0 && en == 0 -> null
    ru >= en -> SlopLang.RU
    else -> SlopLang.EN
  }

  /** Ordinary words by script; a name with an inner capital, an acronym, a number or a path is none of them. */
  private fun scriptCounts(block: String): Pair<Int, Int> {
    var ru = 0
    var en = 0
    for (token in TOKEN.findAll(block)) {
      val word = token.value.trim('\'', '’', '-', '.')
      if (word.length < 2 || word.any { it.isDigit() || it == '_' || it == '.' || it == '/' || it == '\\' }) continue
      if (word.drop(1).any { it.isUpperCase() }) continue
      when {
        word.all { !it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC } -> ru++
        word.all { !it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } -> en++
      }
    }
    return ru to en
  }

  private const val LANGUAGE_EVIDENCE = 4

  /** Headings, tables, list items, link definitions and thematic breaks are not prose sentences. */
  private val SKIP_BLOCK = Regex("^\\s{0,3}(?:#{1,6}\\s|\\||[-*+]\\s|\\d+[.)]\\s|\\[|(?:[-*_]\\s*){3,}$)")
  private val SINGLE_NEWLINE = Regex("(?<!\\n)\\n(?!\\n)")

  /**
   * A sentence ends at `.`, `!`, `?` or `…` followed by space and a capital or an opening quote — unless the period
   * closes an abbreviation or an initial, which is where a naive split invents a sentence of one word.
   *
   * `(?U)`: without it a word boundary knows only ASCII letters, and no Russian abbreviation would ever be recognised.
   */
  private val SENTENCE_SPLIT = Regex(
    "(?U)(?<!\\b\\p{Lu}\\.)(?<!\\b(?:Mr|Ms|Dr|St|vs|etc|e\\.g|i\\.e|Fig|No|т\\.е|т\\.д|т\\.п|т\\. е|т\\. д|т\\. п|др|стр|рис|см|напр|ср|им|ул|гг|г)\\.)" +
    "(?<=[.!?…])[\"'»”)\\]]*\\s+(?=[\\p{Lu}\"'«“(\\[])")

  private fun sentences(masked: String): List<Sentence> {
    // Wrapped lines are one sentence: without flattening, word wrap would read as a stack of short sentences.
    val flow = SINGLE_NEWLINE.replace(masked, " ")
    val paragraphs = paragraphs(masked)
    val out = ArrayList<Sentence>()
    for (block in Regex("[^\\n]+").findAll(flow)) {
      val body = block.value
      if (body.isBlank() || SKIP_BLOCK.containsMatchIn(body)) continue
      // A short line ending in a colon introduces a list or a code block: a label, not a sentence of the rhythm.
      if (body.trimEnd().endsWith(':') && wordsIn(body) <= FRAGMENT_WORDS) continue
      var cursor = 0
      for (piece in body.split(SENTENCE_SPLIT)) {
        val at = body.indexOf(piece, cursor)
        if (at < 0) continue
        cursor = at + piece.length
        if (piece.isBlank()) continue
        val lead = piece.length - piece.trimStart().length
        val start = block.range.first + at + lead
        val end = block.range.first + at + piece.trimEnd().length
        out.add(Sentence(start, end, piece.trim(), paragraphs.firstOrNull { start >= it.start && start < it.end }))
      }
    }
    return out
  }

  private val WHITESPACE = Regex("\\s+")
  private const val MATCH_CHARS = 90

  // --- the checks ---

  private fun lexical(compiled: CompiledRule, context: Context): List<SlopFinding> {
    val out = ArrayList<SlopFinding>()
    for (pattern in compiled.patterns) {
      val m = pattern.matcher(context.masked)
      while (m.find()) {
        if (m.group().isBlank() || m.start() == m.end()) continue
        if (!context.reads(compiled.rule, m.start()) || context.suppressed(compiled.rule.id, m.start())) continue
        out.add(context.finding(compiled.rule, m.start(), m.end()))
      }
    }
    return out
  }

  /** An opener counts only where a sentence starts: the same word in the middle of one is ordinary language. */
  private fun openers(compiled: CompiledRule, context: Context, sentences: List<Sentence>): List<SlopFinding> {
    val out = ArrayList<SlopFinding>()
    for (sentence in sentences) {
      if (compiled.rule.lang != SlopLang.ANY && sentence.paragraph?.lang != compiled.rule.lang) continue
      for (pattern in compiled.patterns) {
        val m = pattern.matcher(sentence.text)
        if (!m.lookingAt()) continue
        val start = sentence.start + m.start()
        if (!context.suppressed(compiled.rule.id, start)) out.add(context.finding(compiled.rule, start, sentence.start + m.end()))
        break
      }
    }
    return out
  }

  /** Some devices are tells only in bulk: every «rather than» is defensible, three in a short post are a habit. */
  private fun density(compiled: CompiledRule, context: Context): SlopFinding? {
    val rule = compiled.rule
    val hits = ArrayList<Pair<Int, Int>>()
    for (pattern in compiled.patterns) {
      val m = pattern.matcher(context.masked)
      while (m.find()) if (context.reads(rule, m.start())) hits.add(m.start() to m.end())
    }
    // Two patterns of one rule can match the same words — a short form of the device and a longer one — and one
    // occurrence counted twice would make a habit out of two sentences.
    hits.sortWith(compareBy({ it.first }, { -it.second }))
    val occurrences = ArrayList<Pair<Int, Int>>()
    for (hit in hits) if (occurrences.isEmpty() || hit.first >= occurrences.last().second) occurrences.add(hit)
    if (occurrences.size < rule.minCount) return null
    val words = if (rule.lang == SlopLang.ANY) words(context.masked)
                else context.paragraphs.filter { it.lang == rule.lang }.sumOf { words(context.masked.substring(it.start, it.end)) }
    if (words == 0) return null
    val rate = occurrences.size * PER_THOUSAND / words
    if (rate < rule.thresholdPer1000) return null
    val first = occurrences.first()
    if (context.suppressed(rule.id, first.first)) return null
    val lines = occurrences.map { context.lineOf(it.first) + 1 }.distinct().take(MAX_LINES_LISTED)
    return context.finding(rule, first.first, first.second).copy(density = SlopDensity(occurrences.size, rate, lines))
  }

  private const val PER_THOUSAND = 1000.0
  private const val MAX_LINES_LISTED = 12

  /** Invisible characters are looked for in the raw text: a zero-width space inside a code fence still breaks a copy. */
  private fun invisible(rule: SlopRule, context: Context): List<SlopFinding> {
    val out = ArrayList<SlopFinding>()
    val seenLines = HashSet<Int>()
    for (removed in com.vibe.agent.security.ContextSanitizer.removable(context.text)) {
      val line = context.lineOf(removed.index)
      if (!seenLines.add(line) || context.suppressed(rule.id, removed.index)) continue
      out.add(context.finding(rule, removed.index, removed.index + Character.charCount(removed.codePoint),
                              "U+%04X".format(removed.codePoint)))
    }
    return out
  }

  /** Four sentences in a row within two words of each other read as a metronome. */
  private fun uniformRhythm(rule: SlopRule, context: Context, sentences: List<Sentence>): List<SlopFinding> {
    val out = ArrayList<SlopFinding>()
    val lengths = sentences.map { wordsIn(it.text) }
    var run = 1
    for (i in 1 until lengths.size) {
      run = if (kotlin.math.abs(lengths[i] - lengths[i - 1]) <= RHYTHM_SPREAD && lengths[i] >= RHYTHM_MIN_WORDS) run + 1 else 1
      if (run == RHYTHM_RUN) {
        val first = sentences[i - RHYTHM_RUN + 1]
        if (!context.suppressed(rule.id, first.start)) out.add(context.finding(rule, first.start, first.end))
      }
    }
    return out
  }

  /** Four short sentences in a row are manufactured punch. */
  private fun stackedFragments(rule: SlopRule, context: Context, sentences: List<Sentence>): List<SlopFinding> {
    val out = ArrayList<SlopFinding>()
    var run = 0
    for ((i, sentence) in sentences.withIndex()) {
      run = if (wordsIn(sentence.text) <= FRAGMENT_WORDS) run + 1 else 0
      if (run == RHYTHM_RUN) {
        val first = sentences[i - RHYTHM_RUN + 1]
        if (!context.suppressed(rule.id, first.start)) out.add(context.finding(rule, first.start, first.end))
      }
    }
    return out
  }

  private const val RHYTHM_RUN = 4
  private const val RHYTHM_SPREAD = 2
  private const val RHYTHM_MIN_WORDS = 8
  private const val FRAGMENT_WORDS = 6

  private val BOLD = Pattern.compile("(?<![\\n*])\\s\\*\\*[^*\\n]{1,60}[^*.:\\n]\\*\\*(?![:.\\n])")
  private val LIST_PREFIX = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s*$")

  /** Bold in the middle of a sentence decorates; a bold label opening a list item is structure. */
  private fun decorativeBold(rule: SlopRule, context: Context): List<SlopFinding> {
    val out = ArrayList<SlopFinding>()
    val m = BOLD.matcher(context.masked)
    while (m.find()) {
      val lineStart = context.masked.lastIndexOf('\n', m.start()) + 1
      if (LIST_PREFIX.matches(context.masked.substring(lineStart, m.start() + 1))) continue
      if (!context.reads(rule, m.start()) || context.suppressed(rule.id, m.start())) continue
      out.add(context.finding(rule, m.start() + 1, m.end()))
    }
    return out
  }

  private val HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+.+$", Pattern.MULTILINE)

  private class Heading(val start: Int, val end: Int)

  private fun headings(masked: String): List<Heading> {
    val out = ArrayList<Heading>()
    val m = HEADING.matcher(masked)
    while (m.find()) out.add(Heading(m.start(), m.end()))
    return out
  }

  /** A heading over one or two sentences is scaffolding. */
  private fun headingStubs(rule: SlopRule, context: Context): List<SlopFinding> {
    val heads = headings(context.masked)
    val out = ArrayList<SlopFinding>()
    for ((i, head) in heads.withIndex()) {
      val end = if (i + 1 < heads.size) heads[i + 1].start else context.masked.length
      val body = context.masked.substring(head.end, end).trim()
      if (body.isEmpty() || wordsIn(body) >= STUB_WORDS || sentences(body).size >= STUB_SENTENCES) continue
      if (!context.suppressed(rule.id, head.start)) out.add(context.finding(rule, head.start, head.end))
    }
    return out
  }

  private const val STUB_WORDS = 25
  private const val STUB_SENTENCES = 2

  /** Title Case in a heading: English style books do not ask for it, and Russian never has it. */
  private fun titleCase(rule: SlopRule, context: Context): List<SlopFinding> {
    val out = ArrayList<SlopFinding>()
    for (head in headings(context.masked)) {
      val words = context.masked.substring(head.start, head.end).trim().trimStart('#').trim().split(WHITESPACE)
        .map { it.trim(':', ',', '.', '(', ')', '«', '»', '"') }
      // A name with an inner capital, an acronym, a version or a path is spelled that way by its owner.
      val content = words.filter { word ->
        word.length > TITLE_WORD_MIN && word.first().isLetter() && word.drop(1).none { it.isUpperCase() } &&
        word.none { it.isDigit() || it == '.' || it == '/' || it == '_' }
      }
      if (content.size < TITLE_WORDS_MIN) continue
      if (!content.all { it.first().isUpperCase() }) continue
      if (!context.suppressed(rule.id, head.start)) out.add(context.finding(rule, head.start, head.end))
    }
    return out
  }

  private const val TITLE_WORD_MIN = 3
  private const val TITLE_WORDS_MIN = 3

  private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")

  internal fun words(text: String): Int = WORD.findAll(text).count()

  private fun wordsIn(sentence: String): Int = sentence.split(WHITESPACE).count { it.isNotEmpty() }

  /** A finding whose matched text is entirely a word the project allows is its real term, not a tell. */
  private fun allowed(finding: SlopFinding, allow: List<Pattern>): Boolean =
    allow.any { it.matcher(finding.match).matches() }

  // --- scoring ---

  /**
   * One problem counts once, at its highest severity: a faux-insight setup that contains a weasel phrase is a single
   * thing to fix, and counting it twice buries the real finding under a near-duplicate.
   */
  internal fun dedupe(findings: List<SlopFinding>): List<SlopFinding> {
    val ranked = findings.sortedWith(compareBy({ -it.severity.ordinal }, { it.start - it.end }, { it.start }))
    val kept = ArrayList<SlopFinding>()
    for (f in ranked) {
      val covered = kept.any { k ->
        (k.start <= f.start && f.end <= k.end) || (k.rule == f.rule && f.start < k.end && k.start < f.end)
      }
      if (!covered) kept.add(f)
    }
    return kept
  }

  internal fun score(findings: List<SlopFinding>, scoring: SlopScoring, words: Int): SlopReport {
    val deductions = findings.groupBy { it.rule }.toSortedMap().map { (rule, group) ->
      val severity = group.first().severity
      val base = scoring.severityPoints.getValue(severity)
      val raw = base + (group.size - 1) * scoring.repeatPoints.getValue(severity)
      SlopDeduction(rule, group.first().name, severity, group.size, minOf(raw, base * scoring.ruleCapMultiplier))
    }
    val value = maxOf(scoring.floor, scoring.start - deductions.sumOf { it.points })
    val rounded = Math.round(value * 10) / 10.0
    val blocking = findings.filter { it.severity > scoring.maxSeverity }.map { it.rule }.distinct().sorted()
    return SlopReport(
      score = rounded,
      passed = rounded >= scoring.passScore && blocking.isEmpty(),
      passScore = scoring.passScore,
      maxSeverity = scoring.maxSeverity,
      blocking = blocking,
      words = words,
      findings = findings,
      deductions = deductions,
    )
  }
}
