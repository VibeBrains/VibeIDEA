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

  /** Reason of the composite rule, see [fetchesAndRuns]; the same code VibeIDE's detector reports. */
  const val FETCH_AND_RUN = "fetch-piped-to-interpreter"

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
    // Match on the basename so a path-qualified binary (/bin/rm, C:\Windows\System32\format) is
    // still classified by the anchored ^binary$ patterns.
    val base = command.substringAfterLast('/').substringAfterLast('\\')
    for (p in DESTRUCTIVE_COMMANDS) if (p.re.containsMatchIn(base)) reasons.add(p.reason)
    for (arg in cleanArgs) for (p in DESTRUCTIVE_ARGS) if (p.re.containsMatchIn(arg)) reasons.add(p.reason)
    if (Regex("^git$", RegexOption.IGNORE_CASE).matches(base)) {
      val joined = cleanArgs.joinToString(" ")
      if (Regex("(^|\\s)push\\b.*--force\\b", RegexOption.IGNORE_CASE).containsMatchIn(joined)) reasons.add("git-push-force")
      if (Regex("(^|\\s)reset\\b.*--hard\\b", RegexOption.IGNORE_CASE).containsMatchIn(joined)) reasons.add("git-reset-hard")
      if (Regex("(^|\\s)clean\\b.*-(f|fd|fdx)\\b", RegexOption.IGNORE_CASE).containsMatchIn(joined)) reasons.add("git-clean-force")
    }
    if (reasons.isNotEmpty()) return Result(Safety.DESTRUCTIVE, reasons, command, cleanArgs)
    if (cleanArgs.isEmpty()) {
      for (p in AMBIGUOUS_COMMANDS) if (p.re.containsMatchIn(base)) return Result(Safety.AMBIGUOUS, listOf(p.reason), command, cleanArgs)
    }
    return Result(Safety.SAFE, emptyList(), command, cleanArgs)
  }

  /**
   * Worst verdict over every simple command in a raw shell line, or null when
   * nothing is destructive. Descends into `$(...)` and backtick command
   * substitutions so a `dd`/`mkfs` hidden inside `sh -c "$(mkfs …)"` is not waved
   * through. Returns the offending segment so the dialog can name the exact command.
   *
   * The composite rule of [fetchesAndRuns] comes last: no single segment of `curl … | sh` is
   * destructive — the line is.
   */
  fun analyzeLine(line: String, depth: Int = 0): Result? {
    for (segment in splitSegments(line)) {
      val verdict = analyze(segment.first, segment.second)
      if (verdict.safety == Safety.DESTRUCTIVE) return verdict
    }
    if (depth < MAX_SUBSTITUTION_DEPTH) {
      for (inner in extractSubstitutions(line)) {
        analyzeLine(inner, depth + 1)?.let { return it }
      }
    }
    if (depth == 0 && fetchesAndRuns(line)) {
      val first = splitSegments(line).firstOrNull()
      return Result(Safety.DESTRUCTIVE, listOf(FETCH_AND_RUN), first?.first ?: line.trim(), first?.second.orEmpty())
    }
    return null
  }

  /**
   * Code fetched from the network and handed straight to an interpreter: `curl … | sh`,
   * `wget -qO- … | python3 -`, `iwr … | iex`, `sh -c "$(curl …)"`, `bash <(curl …)`,
   * `eval "$(curl …)"`.
   *
   * Neither half is destructive: a download changes nothing, and neither does a shell. Together they
   * run whatever the server returns at that second, read by nobody — the mechanism behind downloaded
   * code in skill scripts (arXiv 2604.02837). The rule is on the composition, so it holds with
   * arguments after the interpreter (`| sh -s -- --yes`) and for any interpreter, not only `sh` at
   * the end of the line — the two gaps VibeIDE found in its own line-end pattern (11.09.2026).
   *
   * An interpreter counts only when it takes its PROGRAM from the pipe: `| python3 -m json.tool`
   * pretty-prints an answer and is left alone. Not caught: a download saved to a file and run by the
   * next command (`curl -o i.sh … && sh i.sh`) — telling that from an ordinary build step needs
   * knowing what the file is.
   */
  fun fetchesAndRuns(line: String, depth: Int = 0): Boolean {
    for (chain in pipelines(line)) {
      val fetchAt = chain.indexOfFirst { commandOf(it) in FETCHERS }
      if (fetchAt >= 0 && chain.drop(fetchAt + 1).any { runsStdin(it) }) return true
      if (depth >= MAX_SUBSTITUTION_DEPTH) continue
      for (stage in chain) {
        val command = commandOf(stage) ?: continue
        // What the stage runs as a program: every argument of eval / source / `.`, the program
        // operand of an interpreter.
        val programs = if (command in EVALUATORS) argumentsOf(stage) else listOfNotNull(programArgument(stage))
        for (program in programs) {
          if (extractSubstitutions(program).any { fetches(it, depth + 1) }) return true
          // `sh -c "curl … | sh"`: the operand is a shell line of its own.
          if ((command in EVALUATORS || SHELL.matches(command)) && fetchesAndRuns(program, depth + 1)) return true
        }
      }
    }
    return false
  }

  /** Inner command text of every `$(...)`, `<(...)`, `>(...)` and backtick substitution in [line] (best-effort, non-nesting-aware for backticks). */
  fun extractSubstitutions(line: String): List<String> {
    val found = ArrayList<String>()
    // $( ... ) and the process substitutions <( ... ), >( ... ), with balanced parentheses.
    var i = 0
    while (i < line.length - 1) {
      if ((line[i] == '$' || line[i] == '<' || line[i] == '>') && line[i + 1] == '(') {
        var depth = 1
        var j = i + 2
        while (j < line.length && depth > 0) {
          when (line[j]) { '(' -> depth++; ')' -> depth-- }
          if (depth == 0) break
          j++
        }
        if (j <= line.length) found.add(line.substring(i + 2, minOf(j, line.length)))
        i = j + 1
      } else i++
    }
    // `...` backtick spans.
    val ticks = line.split('`')
    if (ticks.size >= 3) { var k = 1; while (k < ticks.size) { found.add(ticks[k]); k += 2 } }
    return found.filter { it.isNotBlank() }
  }

  private const val MAX_SUBSTITUTION_DEPTH = 3

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

  /** Whether [text] downloads anything — directly or in a substitution of its own. */
  private fun fetches(text: String, depth: Int): Boolean =
    pipelines(text).any { chain -> chain.any { commandOf(it) in FETCHERS } } ||
      (depth < MAX_SUBSTITUTION_DEPTH && extractSubstitutions(text).any { fetches(it, depth + 1) })

  /**
   * Does this stage run what arrives on its standard input as a PROGRAM? `python3 -m json.tool`
   * after `curl` pretty-prints; `python3 -` runs. Without the difference the rule would fire on the
   * most ordinary way to read a JSON answer, and a warning that fires on the ordinary is a warning
   * people learn to click through.
   */
  private fun runsStdin(stage: List<String>): Boolean {
    val at = commandIndex(stage) ?: return false
    val command = baseName(stage[at])
    if (command in INPUT_EVALUATORS) return true
    val interpreter = INTERPRETERS.firstOrNull { it.names.matches(command) } ?: return false
    val args = stage.drop(at + 1)
    for ((i, raw) in args.withIndex()) {
      val arg = if (interpreter.ignoreCase) raw.lowercase() else raw
      if (arg == "-") return true
      // What follows `--` is a script and its arguments; a bare `--` at the end reads stdin.
      if (arg == "--") return i == args.lastIndex
      // An operand is a script file: the pipe is its data, not its program.
      if (!arg.startsWith("-")) return false
      if (arg in interpreter.stdinFlags) return true
      if (arg in interpreter.programFlags) return args.getOrNull(i + 1) == "-"
      // A cluster of short flags: `-xs`, `-ec`, `-lane`.
      if (!arg.startsWith("--") && arg.length > 2) {
        val letters = arg.substring(1)
        if (letters.any { it in interpreter.stdinLetters }) return true
        if (letters.any { it in interpreter.programLetters }) return false
      }
    }
    return true
  }

  /** The operand an interpreter takes its program from — after `-c`/`-e` or not, it is the first one. */
  private fun programArgument(stage: List<String>): String? {
    val at = commandIndex(stage) ?: return null
    if (INTERPRETERS.none { it.names.matches(baseName(stage[at])) }) return null
    return stage.drop(at + 1).firstOrNull { !it.startsWith("-") }
  }

  /** Index of the command in a stage, past `sudo`/`env`-style wrappers and `NAME=value` assignments. */
  private fun commandIndex(stage: List<String>): Int? {
    var i = 0
    while (i < stage.size) {
      val token = stage[i]
      when {
        ASSIGNMENT.matches(token) -> i++
        baseName(token) in WRAPPERS -> {
          i++
          while (i < stage.size && stage[i].startsWith("-")) i++
        }
        else -> return i
      }
    }
    return null
  }

  private fun commandOf(stage: List<String>): String? = commandIndex(stage)?.let { baseName(stage[it]) }

  private fun argumentsOf(stage: List<String>): List<String> = commandIndex(stage)?.let { stage.drop(it + 1) }.orEmpty()

  private fun baseName(token: String): String =
    token.substringAfterLast('/').substringAfterLast('\\').lowercase().removeSuffix(".exe")

  /**
   * A line as the shell groups it: chains (split at `;`, `&&`, `||`, `&`, newline), each a list of
   * pipeline stages (split at a single `|`), each a list of words. Quotes, backticks and
   * `$( … )` / `<( … )` keep their content in one word.
   */
  private fun pipelines(line: String): List<List<List<String>>> {
    val chains = ArrayList<List<List<String>>>()
    var stages = ArrayList<List<String>>()
    var words = ArrayList<String>()
    val word = StringBuilder()
    var quote: Char? = null
    var backtick = false
    var nesting = 0

    fun endWord() { if (word.isNotEmpty()) { words.add(word.toString()); word.setLength(0) } }
    fun endStage() { endWord(); if (words.isNotEmpty()) stages.add(words); words = ArrayList() }
    fun endChain() { endStage(); if (stages.isNotEmpty()) chains.add(stages); stages = ArrayList() }

    var i = 0
    while (i < line.length) {
      val ch = line[i]
      val next = line.getOrNull(i + 1)
      when {
        quote != null -> if (ch == quote) quote = null else word.append(ch)
        ch == '`' -> { backtick = !backtick; word.append(ch) }
        backtick -> word.append(ch)
        ch == '"' || ch == '\'' -> quote = ch
        ch == '\\' && next != null -> { word.append(next); i++ }
        (ch == '$' || ch == '<' || ch == '>') && next == '(' -> { nesting++; word.append(ch).append('('); i++ }
        nesting > 0 -> {
          if (ch == '(') nesting++ else if (ch == ')') nesting--
          word.append(ch)
        }
        ch == '|' && next == '|' -> { endChain(); i++ }
        ch == '|' -> endStage()
        ch == '&' && next == '&' -> { endChain(); i++ }
        // `2>&1`, `&>file`: a redirection, not a separator.
        ch == '&' && (next == '>' || word.endsWith(">") || word.endsWith("<")) -> word.append(ch)
        ch == ';' || ch == '\n' || ch == '&' -> endChain()
        ch == ' ' || ch == '\t' -> endWord()
        else -> word.append(ch)
      }
      i++
    }
    endChain()
    return chains
  }

  /**
   * How an interpreter is told where its program is. [programFlags] take the program from their
   * argument (`-c`, `-e`); [stdinFlags] read it from standard input (`sh -s`); the letters are the
   * same flags inside a cluster of short options.
   */
  private class Interpreter(
    val names: Regex,
    val programFlags: Set<String>,
    val programLetters: String,
    val stdinFlags: Set<String> = emptySet(),
    val stdinLetters: String = "",
    val ignoreCase: Boolean = false,
  )

  private val SHELL = Regex("(ba|z|da|k|mk|fi|a|c|tc)?sh")

  private val INTERPRETERS = listOf(
    Interpreter(SHELL, setOf("-c"), "c", setOf("-s"), "s"),
    Interpreter(Regex("python[0-9.]*|pypy[0-9.]*"), setOf("-c", "-m"), "cm"),
    Interpreter(Regex("node|nodejs|deno|bun"), setOf("-e", "-p", "--eval", "--print"), "ep"),
    Interpreter(Regex("ruby|perl|lua|osascript"), setOf("-e"), "e"),
    Interpreter(Regex("php"), setOf("-r"), "r"),
    Interpreter(Regex("pwsh|powershell"), setOf("-command", "-c", "-encodedcommand", "-ec", "-file", "-f"), "", ignoreCase = true),
  )

  private val FETCHERS = setOf("curl", "wget", "fetch", "aria2c", "iwr", "irm", "invoke-webrequest", "invoke-restmethod")

  /** Run their arguments as shell code. */
  private val EVALUATORS = setOf("eval", "source", ".")

  /** Run their input as code, whatever the flags. */
  private val INPUT_EVALUATORS = setOf("iex", "invoke-expression")

  private val WRAPPERS = setOf("sudo", "doas", "env", "command", "exec", "nohup", "time", "nice")

  private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
}
