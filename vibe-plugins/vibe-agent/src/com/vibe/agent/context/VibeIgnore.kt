// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * `.vibe/ignore` — what the agent neither reads nor searches.
 *
 * The problem it solves is invisible until it bites: a single minified bundle is one line of
 * 400 000 characters, and reading it once eats the context window for the whole turn. The user
 * sees only that the answer got worse.
 *
 * The syntax is the useful subset of gitignore, and deliberately no more — a second full
 * gitignore implementation would drift from git's and lie about which files are ignored:
 * - `#` comments and blank lines are skipped;
 * - `dist/` matches a directory and everything under it;
 * - `*.min.js` matches by name in any directory;
 * - `docs slash star-star slash star.png` matches by path from the project root;
 * - a leading `/` anchors the pattern at the root;
 * - `!pattern` re-includes what an earlier line excluded (last match wins, as in git).
 */
class VibeIgnore private constructor(private val rules: List<Rule>) {
  private class Rule(val regex: Regex, val negated: Boolean, val directoryOnly: Boolean)

  val isEmpty: Boolean get() = rules.isEmpty()

  /** [relativePath] is slash-separated and relative to the project root, without a leading slash. */
  fun isIgnored(relativePath: String, isDirectory: Boolean = false): Boolean {
    if (rules.isEmpty()) return false
    val path = relativePath.trim('/')
    if (path.isEmpty()) return false
    // Last match wins, exactly as in git: that is what makes `!keep.min.js` after `*.min.js` work.
    var ignored = false
    for (rule in rules) if (matches(rule, path, isDirectory)) ignored = !rule.negated
    return ignored
  }

  /**
   * A rule matches the path itself, or any ANCESTOR of it: ignoring `dist/` must ignore
   * `dist/app.js` too, otherwise the rule people write most often would do nothing.
   */
  private fun matches(rule: Rule, path: String, isDirectory: Boolean): Boolean {
    if (rule.regex.matches(path)) return !rule.directoryOnly || isDirectory
    val parts = path.split('/')
    // An ancestor is a directory by definition, so a directory-only rule applies here as well.
    for (i in 0 until parts.size - 1) {
      if (rule.regex.matches(parts.take(i + 1).joinToString("/"))) return true
    }
    return false
  }

  companion object {
    const val FILE = ".vibe/ignore"

    val EMPTY = VibeIgnore(emptyList())

    fun parse(text: String): VibeIgnore {
      val rules = ArrayList<Rule>()
      for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val negated = line.startsWith("!")
        var pattern = if (negated) line.substring(1).trim() else line
        if (pattern.isEmpty()) continue
        val directoryOnly = pattern.endsWith("/")
        pattern = pattern.trim('/')
        if (pattern.isEmpty()) continue
        // A pattern without a slash matches by NAME anywhere; with a slash it is a path from the root.
        val anchored = '/' in pattern
        rules.add(Rule(toRegex(pattern, anchored), negated, directoryOnly))
      }
      return if (rules.isEmpty()) EMPTY else VibeIgnore(rules)
    }

    private fun toRegex(pattern: String, anchored: Boolean): Regex {
      val body = StringBuilder()
      var i = 0
      while (i < pattern.length) {
        val c = pattern[i]
        when {
          c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
            body.append(".*"); i += 2
            if (i < pattern.length && pattern[i] == '/') i++
          }
          c == '*' -> { body.append("[^/]*"); i++ }
          c == '?' -> { body.append("[^/]"); i++ }
          else -> { body.append(Regex.escape(c.toString())); i++ }
        }
      }
      val prefix = if (anchored) "" else "(?:.*/)?"
      return Regex("$prefix$body")
    }
  }
}
