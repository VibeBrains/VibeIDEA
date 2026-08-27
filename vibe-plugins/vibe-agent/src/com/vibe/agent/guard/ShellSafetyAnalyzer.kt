// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

/**
 * Deterministic destructive-command classifier, ported from VibeIDE
 * `nlShellSafetyAnalyzer.ts` verbatim. Pure — no IDE deps, fully unit-tested.
 *
 * The point of splitting the whole line is that the dangerous half of
 * `npm test && rm -rf build` is the half after the `&&`; judging the first word
 * only would wave it through. This is a triage step before a confirm dialog, not
 * a shell — anything it misparses shows up as a stranger-looking command in the
 * dialog the user reads, never as silent permission.
 */
object ShellSafetyAnalyzer {

  enum class Safety { SAFE, DESTRUCTIVE, AMBIGUOUS }

  data class Result(val safety: Safety, val reasons: List<String>, val command: String, val args: List<String>)

  private data class Pat(val re: Regex, val reason: String)

  private val DESTRUCTIVE_COMMANDS = listOf(
    Pat(Regex("^rm$", RegexOption.IGNORE_CASE), "rm-binary"),
    Pat(Regex("^dd$", RegexOption.IGNORE_CASE), "dd-binary"),
    Pat(Regex("^mkfs(\\.|$)", RegexOption.IGNORE_CASE), "mkfs-binary"),
    Pat(Regex("^shred$", RegexOption.IGNORE_CASE), "shred-binary"),
    Pat(Regex("^truncate$", RegexOption.IGNORE_CASE), "truncate-binary"),
    Pat(Regex("^Remove-Item$", RegexOption.IGNORE_CASE), "powershell-remove-item"),
    Pat(Regex("^Format-Volume$", RegexOption.IGNORE_CASE), "powershell-format-volume"),
  )

  private val DESTRUCTIVE_ARGS = listOf(
    Pat(Regex("^--?force\\b", RegexOption.IGNORE_CASE), "force-flag"),
    Pat(Regex("-rf\\b", RegexOption.IGNORE_CASE), "rf-flag"),
    Pat(Regex("-fr\\b", RegexOption.IGNORE_CASE), "fr-flag"),
    Pat(Regex("^[\\\\/]$"), "root-path"),
    Pat(Regex("^~$"), "home-path"),
    Pat(Regex("^\\*$"), "wildcard-only"),
    Pat(Regex("^777$"), "chmod-777"),
    Pat(Regex("^666$"), "chmod-666"),
  )

  private val AMBIGUOUS_COMMANDS = listOf(
    Pat(Regex("^git$", RegexOption.IGNORE_CASE), "git-command-needs-context"),
    Pat(Regex("^npm$", RegexOption.IGNORE_CASE), "npm-command-needs-context"),
    Pat(Regex("^docker$", RegexOption.IGNORE_CASE), "docker-command-needs-context"),
  )

  /** Classify a parsed `(command, args)` pair; most-restrictive verdict wins. */
  fun analyze(command: String, args: List<String>): Result {
    val cleanArgs = args.map { it.trim() }.filter { it.isNotEmpty() }
    val reasons = ArrayList<String>()
    for (p in DESTRUCTIVE_COMMANDS) if (p.re.containsMatchIn(command)) reasons.add(p.reason)
    for (arg in cleanArgs) for (p in DESTRUCTIVE_ARGS) if (p.re.containsMatchIn(arg)) reasons.add(p.reason)
    if (Regex("^git$", RegexOption.IGNORE_CASE).matches(command)) {
      val joined = cleanArgs.joinToString(" ")
      if (Regex("(^|\\s)push\\b.*--force\\b", RegexOption.IGNORE_CASE).containsMatchIn(joined)) reasons.add("git-push-force")
      if (Regex("(^|\\s)reset\\b.*--hard\\b", RegexOption.IGNORE_CASE).containsMatchIn(joined)) reasons.add("git-reset-hard")
      if (Regex("(^|\\s)clean\\b.*-(f|fd|fdx)\\b", RegexOption.IGNORE_CASE).containsMatchIn(joined)) reasons.add("git-clean-force")
    }
    if (reasons.isNotEmpty()) return Result(Safety.DESTRUCTIVE, reasons, command, cleanArgs)
    if (cleanArgs.isEmpty()) {
      for (p in AMBIGUOUS_COMMANDS) if (p.re.containsMatchIn(command)) return Result(Safety.AMBIGUOUS, listOf(p.reason), command, cleanArgs)
    }
    return Result(Safety.SAFE, emptyList(), command, cleanArgs)
  }

  /**
   * Worst verdict over every simple command in a raw shell line, or null when
   * nothing is destructive. Returns the offending segment so the dialog can name
   * the exact command the user is being asked about.
   */
  fun analyzeLine(line: String): Result? {
    for (segment in splitSegments(line)) {
      val verdict = analyze(segment.first, segment.second)
      if (verdict.safety == Safety.DESTRUCTIVE) return verdict
    }
    return null
  }

  /**
   * Split a raw shell line into the simple commands it will run. Deliberately
   * shallow: separators (`&&`, `||`, `;`, `|`, newline) and quotes, nothing else.
   */
  fun splitSegments(line: String): List<Pair<String, List<String>>> {
    val segments = ArrayList<Pair<String, List<String>>>()
    var tokens = ArrayList<String>()
    val current = StringBuilder()
    var quote: Char? = null

    fun endToken() { if (current.isNotEmpty()) { tokens.add(current.toString()); current.setLength(0) } }
    fun endSegment() {
      endToken()
      if (tokens.isNotEmpty()) segments.add(tokens[0] to tokens.drop(1))
      tokens = ArrayList()
    }

    var i = 0
    while (i < line.length) {
      val ch = line[i]
      if (quote != null) {
        if (ch == quote) quote = null else current.append(ch)
        i++; continue
      }
      if (ch == '"' || ch == '\'') { quote = ch; i++; continue }
      if (ch == '\\' && i + 1 < line.length) { current.append(line[i + 1]); i += 2; continue }
      if (ch == '\n' || ch == ';' || ch == '|' || ch == '&') {
        if ((ch == '|' || ch == '&') && i + 1 < line.length && line[i + 1] == ch) i++
        endSegment(); i++; continue
      }
      if (ch == ' ' || ch == '\t') { endToken(); i++; continue }
      current.append(ch); i++
    }
    endSegment()
    return segments
  }
}
