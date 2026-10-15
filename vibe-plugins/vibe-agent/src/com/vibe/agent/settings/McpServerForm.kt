// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.McpServersFile

/**
 * The form that adds a server to `.vibe/mcp.json`: what was typed into four fields, and what is wrong with it
 *
 * Pure, so every rule is a unit test and the panel only shows the answers
 */
object McpServerForm {
  /** The four fields as typed: [args] is one line split like a shell would, [env] is one `NAME=value` per line */
  data class Input(val name: String, val command: String, val args: String, val env: String)

  enum class Problem {
    NO_NAME,
    NAME_TAKEN,
    NO_COMMAND,
    UNCLOSED_QUOTE,

    /** [Invalid.detail] is the number of the line */
    BAD_ENV_LINE,
  }

  sealed interface Verdict {
    data class Valid(val entry: McpServersFile.Entry) : Verdict
    data class Invalid(val problem: Problem, val detail: String = "") : Verdict
  }

  fun validate(input: Input, taken: Set<String>): Verdict {
    val name = input.name.trim()
    if (name.isEmpty()) return Verdict.Invalid(Problem.NO_NAME)
    if (name in taken) return Verdict.Invalid(Problem.NAME_TAKEN, name)
    val command = input.command.trim()
    if (command.isEmpty()) return Verdict.Invalid(Problem.NO_COMMAND)
    val args = splitArgs(input.args) ?: return Verdict.Invalid(Problem.UNCLOSED_QUOTE)
    val env = parseEnv(input.env)
    if (env is Env.Bad) return Verdict.Invalid(Problem.BAD_ENV_LINE, env.line.toString())
    return Verdict.Valid(McpServersFile.Entry(name, command, args, (env as Env.Ok).values))
  }

  fun describe(invalid: Verdict.Invalid): String = when (invalid.problem) {
    Problem.NO_NAME -> t("settings.mcp.form.errName")
    Problem.NAME_TAKEN -> t("settings.mcp.form.errNameTaken", "name" to invalid.detail)
    Problem.NO_COMMAND -> t("settings.mcp.form.errCommand")
    Problem.UNCLOSED_QUOTE -> t("settings.mcp.form.errArgs")
    Problem.BAD_ENV_LINE -> t("settings.mcp.form.errEnv", "line" to invalid.detail)
  }

  /**
   * Splits a line into arguments the way a shell does, minus everything but quotes
   *
   * Spaces separate; a pair of single or double quotes keeps what is inside as part of one argument;
   * there are no escapes and no expansions, so a Windows path in quotes stays as typed
   * Null when a quote is never closed: guessing where the argument ends would start the server with a wrong command line
   */
  fun splitArgs(line: String): List<String>? {
    val args = ArrayList<String>()
    val current = StringBuilder()
    var inToken = false
    var quote: Char? = null
    for (c in line) {
      when {
        quote != null -> if (c == quote) quote = null else current.append(c)
        c == '"' || c == '\'' -> { quote = c; inToken = true }
        c.isWhitespace() -> if (inToken) { args += current.toString(); current.setLength(0); inToken = false }
        else -> { current.append(c); inToken = true }
      }
    }
    if (quote != null) return null
    if (inToken) args += current.toString()
    return args
  }

  sealed interface Env {
    data class Ok(val values: Map<String, String>) : Env

    /** [line] counts from one */
    data class Bad(val line: Int) : Env
  }

  /**
   * `NAME=value` per line; blank lines are skipped, the value is everything after the first `=`, as typed
   *
   * No quotes are stripped and nothing is substituted: the file keeps the value as written, and so does the server's environment
   */
  fun parseEnv(text: String): Env {
    val values = LinkedHashMap<String, String>()
    text.lines().forEachIndexed { index, raw ->
      val line = raw.trim()
      if (line.isEmpty()) return@forEachIndexed
      val eq = line.indexOf('=')
      val key = if (eq > 0) line.substring(0, eq).trim() else ""
      if (!NAME.matches(key)) return Env.Bad(index + 1)
      values[key] = line.substring(eq + 1).trim()
    }
    return Env.Ok(values)
  }

  private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
