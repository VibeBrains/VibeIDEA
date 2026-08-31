// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Project commands: `.vibe/commands.json` travels in the repository, so a colleague who clones it
 * gets the same "поднять окружение" and "прогнать гейты" without being told.
 *
 * The file is code someone else may have written, and it is executed on this machine. Three rules
 * follow, and none of them is optional:
 * - shell metacharacters and invisible characters are refused, not escaped. A command containing a
 *   semicolon or a zero-width joiner is either an attack or a mistake, and guessing which one is
 *   not our job;
 * - a command runs only after the person approved THIS text: the approval is a hash, so editing the
 *   command — by a person or by a pull request — revokes it automatically;
 * - secrets are referenced by name, never written in the file. The value is substituted at run time
 *   and never enters the audit log.
 */
object ProjectCommands {
  const val FILE = ".vibe/commands.json"
  const val MAX_COMMANDS = 50

  /** The only interpolation we perform, shared with everything else that runs things here. */
  private val SECRET_REF = com.vibe.agent.security.SecretRefs.PATTERN

  /** Characters that turn one command into several, or hide what is being run. */
  private val FORBIDDEN = listOf(';', '&', '|', '`', '$', '\n', '\r')

  data class Command(
    val id: String,
    val title: String,
    val command: String,
    val pinned: Boolean = false,
    val order: Int = 0,
    /** Free-form colour name from the file; the UI maps it, unknown values are ignored. */
    val color: String? = null,
  ) {
    /** Secret names this command needs; values are never part of the model. */
    val secretNames: List<String> get() = SECRET_REF.findAll(command).map { it.groupValues[1] }.toList()
  }

  data class Parsed(val commands: List<Command>, val problems: List<String>)

  fun parse(text: String): Parsed {
    val problems = ArrayList<String>()
    val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(text) }.getOrNull()
    val array = (root as? JsonObject)?.get("commands") as? JsonArray
      ?: (root as? JsonArray)
      ?: return Parsed(emptyList(), listOf(PROBLEM_NOT_A_LIST))
    val seen = HashSet<String>()
    val commands = ArrayList<Command>()
    for (element in array) {
      if (commands.size >= MAX_COMMANDS) { problems.add(PROBLEM_TOO_MANY); break }
      val entry = element as? JsonObject ?: run { problems.add(PROBLEM_NOT_AN_OBJECT); continue }
      val id = entry["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      val command = entry["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      if (id.isEmpty() || command.isEmpty()) { problems.add(PROBLEM_NO_ID_OR_COMMAND); continue }
      if (!seen.add(id)) { problems.add(PROBLEM_DUPLICATE + ":" + id); continue }
      val unsafe = unsafeReason(command)
      if (unsafe != null) { problems.add(unsafe + ":" + id); continue }
      commands.add(Command(
        id = id,
        title = entry["title"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: id,
        command = command,
        pinned = entry["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
        order = entry["order"]?.jsonPrimitive?.intOrNull ?: 0,
        color = entry["color"]?.jsonPrimitive?.contentOrNull,
      ))
    }
    // Declared order first, then the file order — a stable list the author controls.
    return Parsed(commands.withIndex().sortedWith(compareBy({ it.value.order }, { it.index })).map { it.value }, problems)
  }

  /** Why this command may not run, or null when it is safe. Refusal, never escaping. */
  fun unsafeReason(command: String): String? {
    if (command.any { it in FORBIDDEN }) return PROBLEM_METACHARACTERS
    if (command.any { isInvisible(it) }) return PROBLEM_INVISIBLE
    return null
  }

  private fun isInvisible(c: Char): Boolean = when (c.code) {
    0x00AD, 0x200B, 0x200C, 0x200D, 0xFEFF -> true
    in 0x2060..0x2064 -> true
    else -> false
  }

  /** The approval is over the exact text: editing the command revokes it, which is the point. */
  fun approvalHash(command: Command): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest((command.id + " " + command.command).toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(32)
  }

  /**
   * Substitutes secrets for running. A name with no value is left AS THE REFERENCE rather than
   * replaced with emptiness: an empty token turns the command into a request that fails in a
   * confusing way, while a visible reference names the missing piece.
   */
  fun substituteSecrets(command: String, lookup: (String) -> String?): String =
    SECRET_REF.replace(command) { match -> lookup(match.groupValues[1]) ?: match.value }

  /** Redacted form for the audit log and the feed: values must never be written down. */
  fun forLog(command: String): String = SECRET_REF.replace(command) { match -> match.value }

  const val PROBLEM_NOT_A_LIST = "not-a-list"
  const val PROBLEM_NOT_AN_OBJECT = "not-an-object"
  const val PROBLEM_NO_ID_OR_COMMAND = "no-id-or-command"
  const val PROBLEM_DUPLICATE = "duplicate"
  const val PROBLEM_METACHARACTERS = "shell-metacharacters"
  const val PROBLEM_INVISIBLE = "invisible-characters"
  const val PROBLEM_TOO_MANY = "too-many"
}
