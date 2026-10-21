// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

/**
 * Project rules the agent must obey: `.vibe/rules` (the family's own folder) and `.cursor/rules` files with the `.mdc`
 * extension (and the legacy `.cursorrules`)
 *
 * The format is not ours, and that is the point: a repository cloned from a colleague already
 * carries its rules, and an agent that cannot see them argues with the project on every turn.
 * Cursor's `.mdc` is a markdown file with a small YAML-ish header:
 *
 * ```
 * ---
 * description: how we name things
 * globs: src per-file globs
 * triggers: phrase one, phrase two
 * alwaysApply: true
 * ---
 * body
 * ```
 *
 * Four kinds of rule, and mixing them up is what makes rule systems useless:
 * - `alwaysApply: true` — goes into every turn;
 * - `globs` — goes in when the turn touches a matching file;
 * - `triggers` — goes in when the user's message contains one of the phrases (see [matchesAnyTrigger]);
 * - none of these — waits to be called by name (`@rule-name`), like Cursor's "agent requested" rules.
 *
 * Any one of the four is enough, they do not veto each other: `alwaysApply: false` next to `triggers` means
 * "off until a phrase or a name asks for it". The `triggers` key is the same one VibeIDE reads from the shared set
 *
 * `@name` inside a body pulls in another rule, once and without cycles. Left unresolved it
 * would be a silent hole: the model would read a reference to instructions it never receives.
 */
object ProjectRules {
  /**
   * The family's native rules folder, shared with VibeIDE and seeded from the common `.vibe` set
   * Read recursively, and wins over [RULES_DIR] when both carry a rule of the same name
   */
  const val NATIVE_RULES_DIR = ".vibe/rules"

  /** How deep [NATIVE_RULES_DIR] is walked: 1 means only the files directly inside it */
  const val NATIVE_MAX_DEPTH = 6

  /** Cap on rule files taken from [NATIVE_RULES_DIR] per folder, so a stray tree cannot flood the prompt */
  const val NATIVE_MAX_FILES = 50

  const val RULES_DIR = ".cursor/rules"
  const val LEGACY_FILE = ".cursorrules"
  const val RULE_EXTENSION = ".mdc"

  /**
   * Правила проекта в формате agents.md — третья граница, которую задаёт сам репозиторий.
   *
   * Формат сложился как соглашение: формальной спеки у него нет, есть договорённость об имени и
   * месте файла (agents.md), и читают его Codex, Cursor, Zed, opencode, goose, Copilot coding
   * agent и Junie. Репозиторий, несущий `AGENTS.md`, до 20.09.2026 не давал нашему агенту ничего:
   * файл лежал только пунктом `@`-меню.
   *
   * Читается ВСЕГДА, а не как запасной вариант при отсутствии `.cursor/rules`. У Claude Code
   * фолбэк осмыслен — там своя `CLAUDE.md` спорит с чужим файлом; у нас обе границы чужие, и
   * молча выбросить одну из них значит потерять инструкции, которые человек написал. Конфликты
   * решает то же правило близости, что и для вложенных правил: ближний к файлу побеждает.
   */
  const val AGENTS_FILE = "AGENTS.md"

  /** Bodies above this are truncated: one runaway rule must not eat the turn's context. */
  const val MAX_RULE_CHARS = 20_000

  data class Rule(
    /** File name without extension — this is what `@name` refers to. */
    val name: String,
    /**
     * Folder the rule came from, relative to the project root; empty for the root rules.
     *
     * A monorepo has one convention per package, and the rule of a package must beat the rule of
     * the repository for files inside it — otherwise the shared root rule quietly overrides the
     * team that wrote the local one.
     */
    val dir: String = "",
    val description: String?,
    val globs: List<String>,
    val alwaysApply: Boolean,
    val body: String,
    /** Path of the file the rule was read from, relative to the project root; empty when it has no file of its own */
    val source: String = "",
    /** Phrases of the user's message that switch the rule on, lowercased; see [matchesAnyTrigger] */
    val triggers: List<String> = emptyList(),
  ) {
    val requestedOnly: Boolean get() = !alwaysApply && globs.isEmpty() && triggers.isEmpty()
  }

  /**
   * Parses one `.mdc` file. A missing or malformed header is not an error: the file is still a
   * rule, just one without metadata — refusing to read it would punish the user for a typo in
   * a format they did not invent.
   */
  fun parse(name: String, text: String, dir: String = "", source: String = ""): Rule {
    val normalized = text.replace("\r\n", "\n")
    if (!normalized.startsWith("---")) return Rule(name, dir, null, emptyList(), false, normalized.trim(), source)
    val end = normalized.indexOf("\n---", 3)
    if (end < 0) return Rule(name, dir, null, emptyList(), false, normalized.trim(), source)
    val header = normalized.substring(3, end)
    val body = normalized.substring(end + 4).trim()
    var description: String? = null
    var globs = emptyList<String>()
    var triggers = emptyList<String>()
    var always = false
    val lines = header.lines()
    var index = 0
    while (index < lines.size) {
      val line = lines[index++]
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val key = line.substring(0, colon).trim().lowercase()
      val raw = line.substring(colon + 1).trim()
      val value = raw.trim('"', '\'')
      when (key) {
        "description" -> description = value.ifBlank { null }
        "globs" -> { globs = splitList(listValue(raw, lines, index)); index = skipDashItems(raw, lines, index) }
        "triggers" -> {
          triggers = splitList(listValue(raw, lines, index)).map { it.lowercase() }
          index = skipDashItems(raw, lines, index)
        }
        "alwaysapply" -> always = value.equals("true", ignoreCase = true)
      }
    }
    return Rule(name, dir, description, globs, always, body, source, triggers)
  }

  private val DASH_ITEM = Regex("^\\s*-\\s+(.*)$")

  /** Value of a list key: the inline text, or for an empty `key:` the `- item` lines below it joined by newlines */
  private fun listValue(raw: String, lines: List<String>, from: Int): String {
    if (raw.isNotEmpty()) return raw
    val items = ArrayList<String>()
    var i = from
    while (i < lines.size) {
      if (lines[i].isBlank()) { i++; continue }
      val item = DASH_ITEM.find(lines[i]) ?: break
      items.add(item.groupValues[1].trim())
      i++
    }
    return items.joinToString("\n")
  }

  /** Index of the first header line after the dash items consumed by [listValue]; unchanged for an inline value */
  private fun skipDashItems(raw: String, lines: List<String>, from: Int): Int {
    if (raw.isNotEmpty()) return from
    var i = from
    var last = from
    while (i < lines.size) {
      if (lines[i].isBlank()) { i++; continue }
      if (DASH_ITEM.find(lines[i]) == null) break
      i++
      last = i
    }
    return last
  }

  /**
   * Header list: comma-separated, bracketed `[a, b]` or one item per line
   *
   * A comma splits only outside quotes, `{}` and `[]`, a newline always splits and resets the depth
   * A quote opens an item only at its start, so `don't stop` keeps the apostrophe
   * Brackets around the whole value are list syntax only when the opening one closes at the very end:
   * `[ab]*.ts` is a glob with a character class
   */
  internal fun splitList(value: String): List<String> {
    var text = value.trim()
    if (text.startsWith("[") && closesAtEnd(text)) text = text.substring(1, text.length - 1)
    val items = ArrayList<String>()
    val item = StringBuilder()
    var depth = 0
    var quote = '\u0000'
    fun flush() {
      item.toString().trim().takeIf { it.isNotEmpty() }?.let { items.add(it) }
      item.setLength(0)
      depth = 0
      quote = '\u0000'
    }
    for (c in text) {
      when {
        c == '\n' -> flush()
        quote != '\u0000' -> if (c == quote) quote = '\u0000' else item.append(c)
        (c == '"' || c == '\'') && item.isBlank() -> quote = c
        c == '{' || c == '[' -> { depth++; item.append(c) }
        (c == '}' || c == ']') -> { if (depth > 0) depth--; item.append(c) }
        c == ',' && depth == 0 -> flush()
        else -> item.append(c)
      }
    }
    flush()
    return items
  }

  /** True when the `[` at the start of [text] is closed by the last character and by nothing earlier */
  private fun closesAtEnd(text: String): Boolean {
    var depth = 0
    for ((i, c) in text.withIndex()) {
      if (c == '[') depth++
      if (c == ']' && --depth == 0) return i == text.length - 1
    }
    return false
  }

  /**
   * Which rules apply to this turn:
   * always-on ones, those whose globs match a touched file, those whose trigger phrase is in the user's text
   * and those called by name in the text
   *
   * Order is stable (declaration order) so the prompt does not reshuffle between turns
   * and break prompt caching for no reason
   */
  fun applicable(rules: List<Rule>, touchedPaths: List<String>, text: String): List<Rule> {
    val mentioned = mentioned(text)
    val chosen = LinkedHashMap<String, Rule>()
    for (rule in rules) {
      val matchesGlob = rule.globs.any { glob -> touchedPaths.any { matchesGlob(it, glob) } }
      if (rule.alwaysApply || matchesGlob || matchesAnyTrigger(rule.triggers, text) || rule.name in mentioned) {
        chosen[rule.name] = rule
      }
    }
    // A rule may call another rule; pulled-in rules follow the ones that asked for them.
    var frontier = chosen.values.toList()
    while (frontier.isNotEmpty()) {
      val next = ArrayList<Rule>()
      for (rule in frontier) {
        for (name in mentioned(rule.body)) {
          if (name in chosen) continue
          val linked = rules.firstOrNull { it.name == name } ?: continue
          chosen[name] = linked
          next.add(linked)
        }
      }
      frontier = next
    }
    return chosen.values.toList()
  }

  /**
   * Whole-phrase, case-insensitive match of any trigger inside [text]
   *
   * Boundaries are Unicode letters and digits, because the regex word boundary knows only ASCII
   * A phrase must not match inside a longer word, and a plain substring test would fire on every word that contains it
   * Case folding is Unicode-aware but keeps the two Russian letters apart, the same as in VibeIDE
   */
  fun matchesAnyTrigger(triggers: List<String>, text: String): Boolean {
    if (text.isEmpty()) return false
    return triggers.any { trigger ->
      trigger.isNotEmpty() &&
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(trigger)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
  }

  /** `@name` references. Emails and `@` inside words are not references — only a whole token. */
  fun mentioned(text: String): Set<String> =
    MENTION.findAll(text).map { it.groupValues[1] }.toSet()

  /** Subset of glob syntax that appears in real rule files: `*`, `**`, `?` and `{a,b}` alternation (nested too) */
  fun matchesGlob(path: String, glob: String): Boolean =
    expandBraces(glob).any { matchesPlainGlob(path, it) }

  /** Expands `{a,b}` groups into plain globs; an unbalanced brace stays a literal */
  internal fun expandBraces(glob: String): List<String> {
    val open = glob.indexOf('{')
    if (open < 0) return listOf(glob)
    var depth = 0
    val parts = ArrayList<String>()
    var start = open + 1
    for (i in open until glob.length) {
      when (glob[i]) {
        '{' -> depth++
        ',' -> if (depth == 1) { parts.add(glob.substring(start, i)); start = i + 1 }
        '}' -> if (--depth == 0) {
          parts.add(glob.substring(start, i))
          val head = glob.substring(0, open)
          val tail = glob.substring(i + 1)
          return parts.flatMap { part -> expandBraces(head + part + tail) }
        }
      }
    }
    return listOf(glob)
  }

  private fun matchesPlainGlob(path: String, glob: String): Boolean {
    val clean = path.replace('\\', '/').trimStart('/')
    val pattern = glob.replace('\\', '/').trimStart('/')
    val body = StringBuilder()
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      when {
        c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
          body.append("(?:.*/)?"); i += 2
          if (i < pattern.length && pattern[i] == '/') i++
        }
        c == '*' -> { body.append("[^/]*"); i++ }
        c == '?' -> { body.append("[^/]"); i++ }
        else -> { body.append(Regex.escape(c.toString())); i++ }
      }
    }
    return Regex(body.toString()).matches(clean)
  }

  /** The prompt block: names and bodies, marked as instructions FROM THE PROJECT. */
  /**
   * Where to look for rules, given the files this turn touches.
   *
   * By ANCESTOR folders of the touched files, never by walking the tree: a naive walk under the
   * project root descends into `node_modules`, `.git` and `out`, and turns «прочитать правила» into
   * a visible pause on every turn. This way the cost is proportional to the files in play, and a
   * monorepo of five hundred packages costs exactly as much as the one package being edited.
   *
   * Ordered root first, deepest last — the order in which the nearest rule wins.
   */
  fun ruleDirsFor(touchedPaths: List<String>): List<String> {
    val dirs = LinkedHashSet<String>()
    dirs.add("")
    for (path in touchedPaths) {
      val normalized = path.replace('\\', '/').trim('/')
      val parts = normalized.split('/').dropLast(1)
      var prefix = ""
      for (part in parts) {
        if (part.isEmpty()) continue
        prefix = if (prefix.isEmpty()) part else "$prefix/$part"
        dirs.add(prefix)
      }
    }
    return dirs.sortedBy { it.count { ch -> ch == '/' } + if (it.isEmpty()) 0 else 1 }
  }

  /**
   * One rule per name: the deepest folder wins.
   *
   * Same name at two levels is not a conflict to report but a deliberate override — that is how
   * per-package conventions are expressed. Reporting it would train people to ignore the report.
   */
  fun nearestWins(rules: List<Rule>): List<Rule> =
    rules.groupBy { it.name }
      .map { (_, sameName) -> sameName.maxByOrNull { depthOf(it.dir) }!! }
      .sortedWith(compareBy({ depthOf(it.dir) }, { it.name }))

  private fun depthOf(dir: String): Int = if (dir.isEmpty()) 0 else dir.count { it == '/' } + 1

  /**
   * A nested rule applies only to files under its own folder.
   *
   * Without this a package rule would travel into turns about other packages — the opposite of why
   * it was written. Root rules apply everywhere, which is what "root" means.
   */
  fun coversPath(rule: Rule, path: String): Boolean {
    if (rule.dir.isEmpty()) return true
    val normalized = path.replace('\\', '/').trim('/')
    return normalized == rule.dir || normalized.startsWith(rule.dir + "/")
  }

  fun promptBlock(rules: List<Rule>, header: String): String {
    if (rules.isEmpty()) return ""
    return buildString {
      appendLine(header)
      for (rule in rules) {
        appendLine("### ${rule.name}${rule.description?.let { " — $it" } ?: ""}")
        appendLine(rule.body.take(MAX_RULE_CHARS))
        appendLine()
      }
    }.trimEnd()
  }

  private val MENTION = Regex("(?<![\\w@/.-])@(?:rule:)?([A-Za-z0-9_-]{1,64})\\b")
}
