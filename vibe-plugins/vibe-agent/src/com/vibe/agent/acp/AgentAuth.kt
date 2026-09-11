// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Signing in to an agent the way the protocol describes it (ACP v1 authentication; Terminal Auth is
 * stable since 20.08.2026).
 *
 * The agent names its ways in `initialize` (`authMethods`) and answers `session/new` with
 * `auth_required` until one of them is done. An `agent` method is driven by the protocol itself —
 * `authenticate` with its id. A `terminal` method is the agent's own program run interactively with
 * the method's arguments; after it the client reconnects and initializes again, and such a method is
 * never passed to `authenticate` — the spec forbids that.
 *
 * Methods are shown exactly as the agent declares them, nothing added and nothing hidden (decision
 * №82): the sign-in is the vendor's own flow, and no credential passes through us.
 *
 * Pure: JSON in, methods and command lines out.
 */
object AgentAuth {
  /** JSON-RPC code of `auth_required` — `RequestError.authRequired` in the protocol's SDK. */
  const val AUTH_REQUIRED = -32000

  private const val TYPE_AGENT = "agent"
  private const val TYPE_TERMINAL = "terminal"

  /** How deep a failure is unwrapped looking for the agent's answer: futures wrap it once or twice. */
  private const val CAUSE_DEPTH = 8

  /** Characters a POSIX shell takes literally in a bare word. */
  private const val POSIX_SAFE = "@%+=:,./-_"

  /** Characters that make cmd.exe or the argument parser split or reinterpret a bare word. */
  private const val WINDOWS_SPECIAL = " \t\"&|<>^%()"

  fun methods(initResult: JsonObject?): List<AuthMethod> =
    (initResult?.get("authMethods") as? JsonArray).orEmpty().mapNotNull { element ->
      val method = element as? JsonObject ?: return@mapNotNull null
      val id = method.string("id") ?: return@mapNotNull null
      // No type means `agent`: that is the protocol's default, not our guess.
      val type = method.string("type") ?: TYPE_AGENT
      AuthMethod(
        id = id,
        name = method.string("name") ?: id,
        description = method.string("description"),
        type = type,
        kind = when (type) {
          TYPE_AGENT -> AuthMethod.Kind.AGENT
          TYPE_TERMINAL -> AuthMethod.Kind.TERMINAL
          else -> AuthMethod.Kind.OTHER
        },
        args = (method["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        env = (method["env"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
          (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap(),
      )
    }

  /** `agentCapabilities.auth.logout`: an object means the agent can log out; absent or null — it cannot. */
  fun logoutSupported(agentCapabilities: JsonObject?): Boolean =
    (agentCapabilities?.get("auth") as? JsonObject)?.get("logout") is JsonObject

  /** Whether a failure is the agent's «log in first», however many futures have wrapped it. */
  fun isAuthRequired(failure: Throwable?): Boolean =
    generateSequence(failure) { it.cause }.take(CAUSE_DEPTH).any { (it as? AcpRpcError)?.code == AUTH_REQUIRED }

  /**
   * What the person runs for a terminal method: the agent's own program with its own arguments and
   * the method's appended, the method's environment over the entry's.
   *
   * The entry's environment is named and not printed: its values can be keys, and a command line
   * ends up in shell histories. The method's environment is the agent's public declaration.
   *
   * @param windows cmd.exe syntax instead of a POSIX shell's.
   */
  fun terminalCommand(config: AgentServerConfig, method: AuthMethod, windows: Boolean): TerminalCommand {
    val argv = listOf(config.command) + config.args + method.args
    val line = if (windows) {
      method.env.entries.joinToString("") { "set \"${it.key}=${it.value}\" && " } + argv.joinToString(" ") { quoteWindows(it) }
    }
    else {
      method.env.entries.joinToString("") { "${it.key}=${quotePosix(it.value)} " } + argv.joinToString(" ") { quotePosix(it) }
    }
    return TerminalCommand(line, config.env.keys.filterNot { it in method.env }.sorted())
  }

  /** @property entryEnvNames variables the agent's entry sets that the command line does not show. */
  data class TerminalCommand(val line: String, val entryEnvNames: List<String>)

  internal fun quotePosix(word: String): String =
    if (word.isNotEmpty() && word.all { it.isAsciiLetterOrDigit() || it in POSIX_SAFE }) word
    else "'" + word.replace("'", "'\\''") + "'"

  internal fun quoteWindows(word: String): String =
    if (word.isNotEmpty() && word.none { it in WINDOWS_SPECIAL }) word
    else "\"" + word.replace("\"", "\\\"") + "\""

  private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
