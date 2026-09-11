// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

/**
 * Deterministic destructive-command classifier, kept in step with VibeIDE `nlShellSafetyAnalyzer.ts`:
 * the same rules, the same reason codes and one test vector in both products — two detectors of one
 * family must not disagree about the same line. Pure — no IDE deps, fully unit-tested.
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
    Pat(Regex("^(?:Clear-Disk|Remove-Partition)$", RegexOption.IGNORE_CASE), "powershell-disk"),
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

  /** `-f` is as much a force push as `--force` — and so is a short-option cluster with `f` (`-uf`). */
  private val GIT_PUSH_FORCE = Regex("(^|\\s)push\\b.*(--force\\b|\\s-[a-zA-Z]*f[a-zA-Z]*\\b)", RegexOption.IGNORE_CASE)
  private val GIT_RESET_HARD = Regex("(^|\\s)reset\\b.*--hard\\b", RegexOption.IGNORE_CASE)
  private val GIT_CLEAN_FORCE = Regex("(^|\\s)clean\\b.*-(f|fd|fdx)\\b", RegexOption.IGNORE_CASE)

  /** A Windows drive, the one argument that tells `format D:` from `npm run format`. */
  private val DRIVE = Regex("[A-Za-z]:")

  /**
   * Classify a parsed `(command, args)` pair; most-restrictive verdict wins.
   *
   * Assignments and wrappers (`sudo`, `env`, `timeout`, `xargs` …) are peeled off first, so the
   * command judged is the one that actually runs: `sudo rm notes.txt` removes the file just the same,
   * and judging the wrapper waved it through.
   */
  fun analyze(command: String, args: List<String>): Result {
    val (target, cleanArgs) = unwrap(command, args.map { it.trim() }.filter { it.isNotEmpty() })
    // Match on the base name so a path-qualified binary (/bin/rm, C:\Windows\System32\format.com) is
    // still classified by the anchored ^binary$ patterns.
    val program = baseName(target)
    val reasons = ArrayList<String>()
    for (p in DESTRUCTIVE_COMMANDS) if (p.re.containsMatchIn(program)) reasons.add(p.reason)
    for (arg in cleanArgs) for (p in DESTRUCTIVE_ARGS) if (p.re.containsMatchIn(arg)) reasons.add(p.reason)
    if (program == "git") {
      val joined = cleanArgs.joinToString(" ")
      if (GIT_PUSH_FORCE.containsMatchIn(joined)) reasons.add("git-push-force")
      if (GIT_RESET_HARD.containsMatchIn(joined)) reasons.add("git-reset-hard")
      if (GIT_CLEAN_FORCE.containsMatchIn(joined)) reasons.add("git-clean-force")
    }
    // Windows `format D:` — the name alone is too common to judge (`npm run format`), the drive is not.
    if (program == "format" && cleanArgs.any { DRIVE.matches(it) }) reasons.add("format-drive")
    if (writesDisk(program, cleanArgs)) reasons.add("disk-tool")
    if (reasons.isNotEmpty()) return Result(Safety.DESTRUCTIVE, reasons, target, cleanArgs)
    if (cleanArgs.isEmpty()) {
      for (p in AMBIGUOUS_COMMANDS) if (p.re.containsMatchIn(program)) return Result(Safety.AMBIGUOUS, listOf(p.reason), target, cleanArgs)
    }
    return Result(Safety.SAFE, emptyList(), target, cleanArgs)
  }

  /**
   * Worst verdict over every simple command in a raw shell line, or null when nothing is
   * destructive. Descends into scripts handed to a shell or `eval` (`bash -c "rm notes.txt"`) and into
   * `$(...)`, `<(...)` and backtick substitutions, so a `dd`/`mkfs` hidden inside `sh -c "$(mkfs …)"` is
   * not waved through. Returns the offending command so the dialog can name it.
   *
   * The composite rule of [fetchesAndRuns] comes last: no single segment of `curl … | sh` is
   * destructive — the line is.
   */
  fun analyzeLine(line: String, depth: Int = 0): Result? {
    val chains = pipelines(line)
    for (stage in chains.flatten()) {
      val verdict = analyze(stage[0], stage.drop(1))
      if (verdict.safety == Safety.DESTRUCTIVE) return verdict
      // `bash -c "rm notes.txt"`, `eval "rm notes.txt"`: the script handed over is a line of its own.
      if (depth < MAX_NESTED_DEPTH) scriptOf(verdict.command, verdict.args)?.let { script -> analyzeLine(script, depth + 1)?.let { return it } }
    }
    if (depth < MAX_NESTED_DEPTH) {
      // `echo $(rm notes.txt)`: a substitution runs before the command that reads its output.
      for (inner in extractSubstitutions(line)) analyzeLine(inner, depth + 1)?.let { return it }
    }
    val offending = findFetchAndRun(chains, depth) ?: return null
    val (command, args) = unwrap(offending[0], offending.drop(1))
    return Result(Safety.DESTRUCTIVE, listOf(FETCH_AND_RUN), command, args)
  }

  /**
   * Code fetched from the network and handed straight to an interpreter: `curl … | sh`,
   * `wget -qO- … | python3 -`, `iwr … | iex`, `iex (iwr …)`, `sh -c "$(curl …)"`, `bash <(curl …)`,
   * `eval "$(curl …)"`, `powershell -Command "irm … | iex"`.
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
  fun fetchesAndRuns(line: String, depth: Int = 0): Boolean = findFetchAndRun(pipelines(line), depth) != null

  /**
   * A command that fetches code and runs it, inside one line of free text — prose or a script.
   *
   * Prose puts words before the command («Сначала выполни: curl … | sh»), and a line-level parse takes
   * the first of them for the command. So every word that can begin such a command — a download, an
   * interpreter, an evaluator, a wrapper — is tried as the start of the line. Returns the command from
   * that word on, or null. Markdown inline code is the caller's to unwrap: a backtick here is shell
   * syntax.
   */
  fun findFetchAndRunInText(line: String): String? {
    for (match in WORD.findAll(line)) {
      // Punctuation that opens prose or a subshell: «(curl», «"eval».
      val lead = LEAD.find(match.value)?.value?.length ?: 0
      if (!startsCommand(baseName(match.value.substring(lead).replace(TRAILING_PUNCTUATION, "")))) continue
      // Sentence punctuation after the command is not part of it: «… | sh.» ends with `sh`.
      val candidate = line.substring(match.range.first + lead).replace(SENTENCE_END, "")
      if (fetchesAndRuns(candidate)) return candidate
    }
    return null
  }

  /** Inner lines of every `$( … )`, `<( … )`, `>( … )` and backtick span: they run before the command around them. */
  fun extractSubstitutions(line: String): List<String> {
    val found = ArrayList<String>()
    var i = 0
    while (i + 1 < line.length) {
      if ((line[i] == '$' || line[i] == '<' || line[i] == '>') && line[i + 1] == '(') {
        var open = 1
        var j = i + 2
        while (j < line.length) {
          if (line[j] == '(') open++ else if (line[j] == ')' && --open == 0) break
          j++
        }
        found.add(line.substring(i + 2, minOf(j, line.length)))
        i = j
      }
      i++
    }
    // Only complete backtick pairs: an unterminated one is not a substitution.
    val spans = line.split('`')
    var k = 1
    while (k + 1 < spans.size) { found.add(spans[k]); k += 2 }
    return found.filter { it.isNotBlank() }
  }

  /** The simple commands of a raw line, in order, without their grouping — see [pipelines]. */
  fun splitSegments(line: String): List<Pair<String, List<String>>> =
    pipelines(line).flatten().map { it[0] to it.drop(1) }

  private const val MAX_NESTED_DEPTH = 3

  /**
   * A line as the shell groups it: chains (split at `;`, `&&`, `||`, `&`, newline, carriage return),
   * each a list of pipeline stages (split at `|` and `|&`), each a list of words.
   *
   * Deliberately shallow: separators, quotes, backslashes, and `$( … )` / `<( … )` / backticks kept
   * whole as one word, nothing else. `2>&1` and `&>file` are redirections, not separators. `#` is NOT
   * a comment here: cmd.exe has none, and `echo # & format D:` runs the format there.
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
        nesting > 0 -> {
          // Inside `$( … )` the text is kept verbatim — it is a line of its own, judged later.
          // Quotes only stop the parentheses inside them from counting.
          word.append(ch)
          when {
            ch == '\\' && next != null -> { word.append(next); i++ }
            quote != null -> if (ch == quote) quote = null
            ch == '"' || ch == '\'' -> quote = ch
            ch == '(' -> nesting++
            ch == ')' -> nesting--
          }
        }
        quote != null -> if (ch == quote) quote = null else word.append(ch)
        ch == '`' -> { backtick = !backtick; word.append(ch) }
        backtick -> word.append(ch)
        ch == '"' || ch == '\'' -> quote = ch
        ch == '\\' && next != null -> { word.append(next); i++ }
        (ch == '$' || ch == '<' || ch == '>') && next == '(' -> { nesting = 1; word.append(ch).append(next); i++ }
        ch == '|' && next == '|' -> { endChain(); i++ }
        // `|&` pipes stderr along with stdout: still one pipeline.
        ch == '|' -> { endStage(); if (next == '&') i++ }
        ch == '&' && next == '&' -> { endChain(); i++ }
        // `2>&1`, `&>file`: a redirection, not a separator.
        ch == '&' && (next == '>' || word.endsWith(">") || word.endsWith("<")) -> word.append(ch)
        ch == ';' || ch == '\n' || ch == '\r' || ch == '&' -> endChain()
        ch == ' ' || ch == '\t' -> endWord()
        else -> word.append(ch)
      }
      i++
    }
    endChain()
    return chains
  }

  /** The line a command runs as code: the script of `sh -c "<script>"` / `pwsh -Command`, the words of `eval`. */
  private fun scriptOf(command: String, args: List<String>): String? {
    val program = baseName(command)
    if (program == "eval") return args.takeIf { it.isNotEmpty() }?.joinToString(" ")
    if (!SHELL.matches(program) && !POWERSHELL.matches(program)) return null
    val at = args.indexOfFirst { SCRIPT_FLAG.matches(it) || COMMAND_FLAG.matches(it) }
    return if (at >= 0 && at + 1 < args.size) args[at + 1] else null
  }

  /**
   * The stage that fetches code and runs it, or null: the download for a pipe, the interpreter or
   * evaluator for the other forms — the one the dialog should name. See [fetchesAndRuns].
   */
  private fun findFetchAndRun(chains: List<List<List<String>>>, depth: Int): List<String>? {
    for (chain in chains) {
      val fetchAt = chain.indexOfFirst { programOf(it) in FETCHERS }
      if (fetchAt >= 0 && chain.drop(fetchAt + 1).any { runsStdin(it) }) return chain[fetchAt]
      if (depth >= MAX_NESTED_DEPTH) continue
      for (stage in chain) {
        val (command, args) = unwrap(stage[0], stage.drop(1))
        val program = baseName(command)
        if (program in INPUT_EVALUATORS && POWERSHELL_DOWNLOAD.containsMatchIn(args.joinToString(" "))) return stage
        // What the stage runs as a program: every argument of eval / source / `.`, the program
        // operand of an interpreter.
        val programs = if (program in EVALUATORS) args else listOfNotNull(programOperand(program, args))
        for (text in programs) {
          if (extractSubstitutions(text).any { fetches(it, depth + 1) }) return stage
          // `sh -c "curl … | sh"`: the operand is a line of its own.
          if ((program in EVALUATORS || SHELL.matches(program) || POWERSHELL.matches(program)) &&
              findFetchAndRun(pipelines(text), depth + 1) != null) return stage
        }
      }
    }
    return null
  }

  /** Whether [text] downloads anything — directly or in a substitution of its own. */
  private fun fetches(text: String, depth: Int): Boolean =
    pipelines(text).any { chain -> chain.any { programOf(it) in FETCHERS } } ||
      (depth < MAX_NESTED_DEPTH && extractSubstitutions(text).any { fetches(it, depth + 1) })

  /**
   * Does this stage run what arrives on its standard input as a PROGRAM? `python3 -m json.tool`
   * after `curl` pretty-prints; `python3 -` runs. Without the difference the rule would fire on the
   * most ordinary way to read a JSON answer, and a warning that fires on the ordinary is a warning
   * people learn to click through.
   */
  private fun runsStdin(stage: List<String>): Boolean {
    val (command, args) = unwrap(stage[0], stage.drop(1))
    val program = baseName(command)
    if (program in INPUT_EVALUATORS) return true
    val interpreter = INTERPRETERS.firstOrNull { it.names.matches(program) } ?: return false
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
  private fun programOperand(program: String, args: List<String>): String? =
    if (INTERPRETERS.any { it.names.matches(program) }) args.firstOrNull { !it.startsWith("-") } else null

  /**
   * Peel assignments and wrappers — with their options and `timeout`'s duration — off a command.
   *
   * The value options matter as much as the wrappers: in `sudo -u deploy rm`, `deploy` is not the command.
   */
  private fun unwrap(command: String, args: List<String>): Pair<String, List<String>> {
    var tokens = listOf(command) + args
    var depth = 0
    while (depth < MAX_WRAPPER_DEPTH && tokens.size > 1) {
      while (tokens.size > 1 && ASSIGNMENT.matches(tokens[0])) tokens = tokens.drop(1)
      val wrapper = baseName(tokens[0])
      val withValue = WRAPPERS[wrapper]
      // `command -v rm` looks the name up instead of running it.
      if (withValue == null || tokens.size < 2 || (wrapper == "command" && LOOKUP.matches(tokens[1]))) break
      tokens = tokens.drop(1)
      while (tokens.size > 1) {
        val token = tokens[0]
        if (token in withValue && tokens.size > 2) tokens = tokens.drop(2)
        else if (token.startsWith("-") || ASSIGNMENT.matches(token)) tokens = tokens.drop(1)
        else break
      }
      if (wrapper == "timeout" && tokens.size > 1 && DURATION.matches(tokens[0])) tokens = tokens.drop(1)
      depth++
    }
    return tokens[0] to tokens.drop(1)
  }

  /**
   * Whether a disk tool is asked to change a disk rather than to show one. `fdisk -l` is how people
   * look at partitions, and a dialog on it would teach them to click through the one on `fdisk /dev/sda`.
   */
  private fun writesDisk(program: String, args: List<String>): Boolean {
    if (program == "diskutil") {
      val verb = args.getOrElse(0) { "" }
      val target = args.getOrElse(1) { "" }
      return DISKUTIL_WRITE.matches(verb) || (DISKUTIL_APFS.matches(verb) && DISKUTIL_APFS_WRITE.matches(target))
    }
    if (!DISK_TOOLS.matches(program)) return false
    val onlyLooks = args.all { DISK_LOOKING_ARG.matches(it) }
    // `wipefs` lists signatures unless told to erase them; the others have to be asked to list.
    return !onlyLooks || (program != "wipefs" && args.none { DISK_LISTING_ARG.matches(it) })
  }

  private fun programOf(stage: List<String>): String = baseName(unwrap(stage[0], stage.drop(1)).first)

  /** Words that can begin a fetch-and-run command. */
  private fun startsCommand(program: String): Boolean =
    program in FETCHERS || program in EVALUATORS || program in INPUT_EVALUATORS || program in WRAPPERS ||
      INTERPRETERS.any { it.names.matches(program) }

  /** Lower-case program name without directory or `.exe`/`.com`: `/usr/bin/Bash.exe` → `bash`. */
  private fun baseName(token: String): String =
    token.substringAfterLast('/').substringAfterLast('\\').replace(EXECUTABLE_SUFFIX, "").lowercase()

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
  private val POWERSHELL = Regex("pwsh|powershell")

  private val INTERPRETERS = listOf(
    Interpreter(SHELL, setOf("-c"), "c", setOf("-s"), "s"),
    Interpreter(Regex("python[0-9.]*|pypy[0-9.]*"), setOf("-c", "-m"), "cm"),
    Interpreter(Regex("node|nodejs|deno|bun"), setOf("-e", "-p", "--eval", "--print"), "ep"),
    Interpreter(Regex("ruby|perl|lua|osascript"), setOf("-e"), "e"),
    Interpreter(Regex("php"), setOf("-r"), "r"),
    Interpreter(POWERSHELL, setOf("-command", "-c", "-encodedcommand", "-ec", "-file", "-f"), "", ignoreCase = true),
  )

  /** Programs that fetch from the network and print what they fetched. */
  private val FETCHERS = setOf("curl", "wget", "fetch", "aria2c", "iwr", "irm", "invoke-webrequest", "invoke-restmethod")

  /** Run their arguments as shell code. */
  private val EVALUATORS = setOf("eval", "source", ".")

  /** Run their input as code, whatever the flags — and their argument, which is PowerShell too. */
  private val INPUT_EVALUATORS = setOf("iex", "invoke-expression")

  /** A download inside a PowerShell expression: `iex (iwr …)`, `iex (New-Object Net.WebClient).DownloadString(…)`. */
  private val POWERSHELL_DOWNLOAD = Regex(
    "(?:^|[\\s(])(?:iwr|irm|curl|wget|invoke-webrequest|invoke-restmethod)\\b|\\.download(?:string|data|file)\\s*\\(",
    RegexOption.IGNORE_CASE,
  )

  /** `sh -c`, `bash -xc`, `pwsh -Command`: the flag after which a shell's script follows. */
  private val SCRIPT_FLAG = Regex("-[a-zA-Z]*c[a-zA-Z]*")
  private val COMMAND_FLAG = Regex("-command", RegexOption.IGNORE_CASE)

  /**
   * Commands that run the command after them, each with its options that take a value — the same
   * table as VibeIDE's.
   */
  private val WRAPPERS: Map<String, Set<String>> = mapOf(
    "sudo" to setOf("-u", "-g", "-h", "-p", "-U", "-C", "-D", "-r", "-t", "-T"),
    "doas" to setOf("-u", "-C"),
    "env" to setOf("-u", "-C", "-S"),
    "nice" to setOf("-n"),
    "timeout" to setOf("-s", "-k"),
    "stdbuf" to setOf("-i", "-o", "-e"),
    "xargs" to setOf("-I", "-n", "-P", "-L", "-s", "-d", "-E", "-a"),
    "nohup" to emptySet(),
    "time" to emptySet(),
    "command" to emptySet(),
    "exec" to emptySet(),
  )

  /** How many wrappers deep a command is looked for: `sudo env FOO=1 nice rm` is three. */
  private const val MAX_WRAPPER_DEPTH = 4

  /** `NAME=value` before a command sets its environment; it is not the command. */
  private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")

  /** `timeout`'s duration: `30`, `2.5m`, `1h`. */
  private val DURATION = Regex("\\d+(?:\\.\\d+)?[smhd]?")

  /** `command -v` / `-V`: a lookup, not a run. */
  private val LOOKUP = Regex("-[vV]")

  private val EXECUTABLE_SUFFIX = Regex("\\.(?:exe|com)$", RegexOption.IGNORE_CASE)

  /** Disk tools: one wrong device name is a lost disk. */
  private val DISK_TOOLS = Regex("fdisk|sfdisk|gdisk|sgdisk|parted|wipefs|diskpart")

  /** Arguments that only look: list, print, help, script mode, the device looked at. */
  private val DISK_LOOKING_ARG = Regex("-l|--list|-p|--print|print|-s|--script|-h|--help|-V|--version|/dev/\\S+")
  private val DISK_LISTING_ARG = Regex("-l|--list|-p|--print|print")
  private val DISKUTIL_WRITE = Regex("erase\\w*|zerodisk|randomdisk|secureerase|partitiondisk|reformat", RegexOption.IGNORE_CASE)
  private val DISKUTIL_APFS = Regex("apfs", RegexOption.IGNORE_CASE)
  private val DISKUTIL_APFS_WRITE = Regex("delete\\w*|erase\\w*", RegexOption.IGNORE_CASE)

  private val WORD = Regex("\\S+")
  private val LEAD = Regex("^[(\"'«]*")
  private val TRAILING_PUNCTUATION = Regex("[.,:;!?»\"')]+$")
  private val SENTENCE_END = Regex("[\\s.,:!?»]+$")
}
