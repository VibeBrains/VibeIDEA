// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * Project rules the agent must obey: `.cursor/rules` files with the `.mdc` extension (and the legacy `.cursorrules`).
 *
 * The format is not ours, and that is the point: a repository cloned from a colleague already
 * carries its rules, and an agent that cannot see them argues with the project on every turn.
 * Cursor's `.mdc` is a markdown file with a small YAML-ish header:
 *
 * ```
 * ---
 * description: how we name things
 * globs: src per-file globs
 * alwaysApply: true
 * ---
 * body
 * ```
 *
 * Three kinds of rule, and mixing them up is what makes rule systems useless:
 * - `alwaysApply: true` — goes into every turn;
 * - `globs` — goes in when the turn touches a matching file;
 * - neither — waits to be called by name (`@rule-name`), like Cursor's "agent requested" rules.
 *
 * `@name` inside a body pulls in another rule, once and without cycles. Left unresolved it
 * would be a silent hole: the model would read a reference to instructions it never receives.
 */
object ProjectRules {
  const val RULES_DIR = ".cursor/rules"
  const val LEGACY_FILE = ".cursorrules"
  const val RULE_EXTENSION = ".mdc"

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
  ) {
    val requestedOnly: Boolean get() = !alwaysApply && globs.isEmpty()
  }

  /**
   * Parses one `.mdc` file. A missing or malformed header is not an error: the file is still a
   * rule, just one without metadata — refusing to read it would punish the user for a typo in
   * a format they did not invent.
   */
  fun parse(name: String, text: String, dir: String = ""): Rule {
    val normalized = text.replace("\r\n", "\n")
    if (!normalized.startsWith("---")) return Rule(name, dir, null, emptyList(), false, normalized.trim())
    val end = normalized.indexOf("\n---", 3)
    if (end < 0) return Rule(name, dir, null, emptyList(), false, normalized.trim())
    val header = normalized.substring(3, end)
    val body = normalized.substring(end + 4).trim()
    var description: String? = null
    var globs = emptyList<String>()
    var always = false
    for (line in header.lines()) {
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val key = line.substring(0, colon).trim().lowercase()
      val value = line.substring(colon + 1).trim().trim('"', '\'')
      when (key) {
        "description" -> description = value.ifBlank { null }
        "globs" -> globs = value.trim('[', ']').split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        "alwaysapply" -> always = value.equals("true", ignoreCase = true)
      }
    }
    return Rule(name, dir, description, globs, always, body)
  }

  /**
   * Which rules apply to this turn: always-on ones, those whose globs match a touched file, and
   * those called by name in the text. Order is stable (declaration order) so the prompt does not
   * reshuffle between turns and break prompt caching for no reason.
   */
  fun applicable(rules: List<Rule>, touchedPaths: List<String>, text: String): List<Rule> {
    val mentioned = mentioned(text)
    val chosen = LinkedHashMap<String, Rule>()
    for (rule in rules) {
      val matchesGlob = rule.globs.any { glob -> touchedPaths.any { matchesGlob(it, glob) } }
      if (rule.alwaysApply || matchesGlob || rule.name in mentioned) chosen[rule.name] = rule
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

  /** `@name` references. Emails and `@` inside words are not references — only a whole token. */
  fun mentioned(text: String): Set<String> =
    MENTION.findAll(text).map { it.groupValues[1] }.toSet()

  /** Subset of glob syntax that appears in real rule files: `*`, `**`, `?`. */
  fun matchesGlob(path: String, glob: String): Boolean {
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

  private val MENTION = Regex("(?<![\\w@/.-])@([A-Za-z0-9_-]{1,64})\\b")
}
