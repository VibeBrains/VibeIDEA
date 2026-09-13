// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.guard.ShellSafetyAnalyzer

/**
 * Reads the commands a skill would have the agent run and names the ones that bring in code nobody
 * reviewed. Pure: text in, findings out.
 *
 * The agent runs a skill's scripts with the person's authority, and the model sees their output,
 * never their source (arXiv 2604.02837). So the things worth saying before approval are the ones a
 * reviewer skims past: a download piped straight into an interpreter, a package runner without a
 * version — `npx tool` executes whatever is published under that name today — and inline script
 * dependencies (PEP 723) left without a pin.
 */
object SkillCodeScan {
  enum class Kind { FETCH_AND_RUN, UNPINNED_RUNNER, UNPINNED_PEP723 }

  data class Finding(val kind: Kind, val file: String, val detail: String)

  /** How much of an offending command to quote: enough to find it in the file. */
  const val DETAIL_CHARS = 120

  /**
   * SKILL.md: every command in its fenced code blocks, and a download handed to an interpreter
   * anywhere in its prose. Prose is the model's instructions — «Сначала выполни: curl … | sh» is an
   * instruction, not discussion (VibeIDE reads prose the same way, 11.09.2026). The other findings
   * stay code-only: a package named in a sentence is not a command.
   */
  fun scanSkill(body: String): List<Finding> {
    val (code, prose) = split(body)
    val found = scanCommands(SkillPackage.SKILL_FILE, code)
    if (found.any { it.kind == Kind.FETCH_AND_RUN }) return found
    // Inline code is unwrapped first: to Markdown a backtick is a frame, to the shell it is syntax.
    val inProse = prose.firstNotNullOfOrNull { ShellSafetyAnalyzer.findFetchAndRunInText(INLINE_CODE.replace(it, "$1")) }
      ?: return found
    return found + Finding(Kind.FETCH_AND_RUN, SkillPackage.SKILL_FILE, inProse.take(DETAIL_CHARS))
  }

  /** A script is code throughout: every line, plus its PEP 723 header. */
  fun scanScript(path: String, text: String): List<Finding> =
    scanCommands(path, text.lines()) + listOfNotNull(unpinnedInlineDependency(text)?.let { Finding(Kind.UNPINNED_PEP723, path, it) })

  /** Lines inside ``` or ~~~ fences. */
  fun fencedCode(markdown: String): List<String> = split(markdown).first

  /** Fenced code and prose, each in order; a fence line itself is neither. */
  private fun split(markdown: String): Pair<List<String>, List<String>> {
    val code = ArrayList<String>()
    val prose = ArrayList<String>()
    var fence: String? = null
    for (line in markdown.lines()) {
      val trimmed = line.trimStart()
      val marker = FENCES.firstOrNull { trimmed.startsWith(it) }
      when {
        fence == null && marker != null -> fence = marker
        fence != null && trimmed.startsWith(fence) -> fence = null
        fence != null -> code.add(line)
        else -> prose.add(line)
      }
    }
    return code to prose
  }

  private val INLINE_CODE = Regex("`([^`]*)`")

  /**
   * `npx <package>` or `uvx <package>` without an exact version, as «npx package», or null.
   *
   * A tag is not a version: `@latest` names whatever was published last, which is the problem.
   */
  fun unpinnedRunner(line: String): String? {
    for ((command, args) in ShellSafetyAnalyzer.splitSegments(line)) {
      when (command.substringAfterLast('/').lowercase()) {
        "npx" -> npxPackage(args)?.takeUnless { npmPinned(it) }?.let { return "npx $it" }
        "uvx" -> uvxPackage(args)?.takeUnless { pythonPinned(it) }?.let { return "uvx $it" }
      }
    }
    return null
  }

  /** The first dependency of a PEP 723 block without an exact version, or null. */
  fun unpinnedInlineDependency(text: String): String? {
    val lines = text.lines()
    val start = lines.indexOfFirst { PEP723_START.matches(it.trim()) }
    if (start < 0) return null
    val block = StringBuilder()
    for (line in lines.drop(start + 1)) {
      if (PEP723_END.matches(line.trim())) break
      block.append(line.trimStart().removePrefix("#").removePrefix(" ")).append('\n')
    }
    return dependencies(block.toString()).firstOrNull { !exactRequirement(it) }
  }

  private fun scanCommands(file: String, lines: List<String>): List<Finding> {
    val found = LinkedHashMap<Kind, Finding>()
    for (line in lines) {
      // `$ ` is a prompt in documentation, not part of the command.
      val command = line.trim().removePrefix("$ ").trim()
      if (command.isEmpty() || command.startsWith("#")) continue
      // One finding of a kind per file: it sends someone to read the file, and the file says the rest.
      if (Kind.FETCH_AND_RUN !in found && ShellSafetyAnalyzer.fetchesAndRuns(command)) {
        found[Kind.FETCH_AND_RUN] = Finding(Kind.FETCH_AND_RUN, file, command.take(DETAIL_CHARS))
      }
      if (Kind.UNPINNED_RUNNER !in found) {
        unpinnedRunner(command)?.let { found[Kind.UNPINNED_RUNNER] = Finding(Kind.UNPINNED_RUNNER, file, it.take(DETAIL_CHARS)) }
      }
    }
    return found.values.toList()
  }

  /** The package npx runs: the value of `-p`/`--package`, else its first operand. */
  private fun npxPackage(args: List<String>): String? {
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        arg == "-p" || arg == "--package" -> return args.getOrNull(i + 1)
        arg.startsWith("--package=") -> return arg.substringAfter('=')
        arg.startsWith("-") -> i++
        else -> return arg
      }
    }
    return null
  }

  /** The package uvx runs: the value of `--from`, else its first operand past options that take a value. */
  private fun uvxPackage(args: List<String>): String? {
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        arg == "--from" -> return args.getOrNull(i + 1)
        arg.startsWith("--from=") -> return arg.substringAfter('=')
        arg in UVX_VALUE_OPTIONS -> i += 2
        arg.startsWith("-") -> i++
        else -> return arg
      }
    }
    return null
  }

  private fun local(spec: String): Boolean = spec.startsWith(".") || spec.startsWith("/") || spec.startsWith("file:")

  /** `name@1.2.3` or `@scope/name@1.2.3`; a range or a tag is not a pin. */
  private fun npmPinned(spec: String): Boolean {
    if (local(spec)) return true
    val at = spec.lastIndexOf('@')
    if (at <= 0) return false
    return EXACT_SEMVER.matches(spec.substring(at + 1))
  }

  /** `name==1.2` or `name@1.2`, or a commit; `@latest` is not a pin. */
  private fun pythonPinned(spec: String): Boolean {
    if (local(spec)) return true
    if ("==" in spec) return spec.substringAfter("==").isNotBlank()
    val at = spec.lastIndexOf('@')
    if (at <= 0) return false
    val ref = spec.substring(at + 1)
    return PYTHON_VERSION.matches(ref) || COMMIT.matches(ref)
  }

  /** `==` and `===` pin; a direct reference (`name @ url`) names one artifact. */
  private fun exactRequirement(requirement: String): Boolean = "==" in requirement || "@ " in requirement

  /** The quoted items of `dependencies = [ … ]`; brackets inside quotes (`pkg[extra]`) do not end the list. */
  private fun dependencies(toml: String): List<String> {
    val at = toml.indexOf("dependencies")
    if (at < 0) return emptyList()
    val open = toml.indexOf('[', at)
    if (open < 0) return emptyList()
    val found = ArrayList<String>()
    val item = StringBuilder()
    var quote: Char? = null
    for (ch in toml.substring(open + 1)) {
      when {
        quote != null && ch == quote -> { found.add(item.toString()); item.setLength(0); quote = null }
        quote != null -> item.append(ch)
        ch == '"' || ch == '\'' -> quote = ch
        ch == ']' -> break
      }
    }
    return found
  }

  private val FENCES = listOf("```", "~~~")
  private val PEP723_START = Regex("#\\s*///\\s*script")
  private val PEP723_END = Regex("#\\s*///")
  private val EXACT_SEMVER = Regex("v?\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?")
  // Each repetition of the group starts with a separator, so every character has exactly one way to
  // match. The previous `(\\.\\d+)*([.-]?[A-Za-z0-9]+)*` was `(a+)*`: on an almost-version from a
  // foreign skill (`uvx pkg@1aaaa…a!`) backtracking doubled with every character (VibeIDE, 11.09.2026).
  private val PYTHON_VERSION = Regex("v?\\d[A-Za-z0-9]*([.-][A-Za-z0-9]+)*")
  private val COMMIT = Regex("[0-9a-f]{7,40}")
  private val UVX_VALUE_OPTIONS = setOf(
    "--with", "--with-editable", "--with-requirements", "--python", "-p", "--index", "--index-url",
    "--extra-index-url", "--constraint", "--constraints", "--overrides", "--directory", "--cache-dir",
    "--config-file",
  )
}
