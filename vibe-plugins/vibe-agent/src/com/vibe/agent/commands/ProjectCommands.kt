// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.security.MessageDigest

/**
 * Project commands: `.vibe/commands.json` travels in the repository, so a colleague who clones it
 * gets the same «bring the environment up» and «run the gates» without being told.
 *
 * The format is shared with VibeIDE (its `docs/manuals/projectCommandsSpec.md` is the canon): one file has to
 * run the same command in both IDEs. Our older spelling — `title` and the whole line in `command` — stays a synonym.
 *
 * The file is code someone else may have written, and it is executed on this machine. Three rules
 * follow, and none of them is optional:
 * - shell metacharacters and invisible characters are refused, not escaped. A command containing a
 *   semicolon or a zero-width joiner is either an attack or a mistake, and guessing which one is
 *   not our job; `"shell": true` is the author saying out loud that the metacharacters are meant;
 * - a command runs only after the person approved THIS program, arguments, folder and environment:
 *   the approval is a hash, so editing any of them — by a person or by a pull request — revokes it;
 * - secrets are referenced by name, never written in the file. The value is substituted at run time
 *   and never enters the audit log.
 */
object ProjectCommands {
  const val FILE = ".vibe/commands.json"
  const val MAX_COMMANDS = 50

  /** How the output is shown; VibeIDE's three values, of which the Run tool window knows two ([ProjectCommandRunner]) */
  enum class Terminal(val wire: String) {
    INTEGRATED("integrated"), BACKGROUND("background"), EXTERNAL("external");

    companion object {
      fun parse(wire: String): Terminal? = entries.firstOrNull { it.wire == wire }
    }
  }

  data class Command(
    val id: String,
    val name: String,
    /** The program alone; its arguments are [args] */
    val command: String,
    val args: List<String> = emptyList(),
    val description: String? = null,
    /** Relative to the project root; null is the root itself */
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val pinned: Boolean = false,
    /** Null sorts last, as in VibeIDE */
    val order: Double? = null,
    /** A theme colour name from the file (`terminal.ansiBlue`); [colorKey] maps it, unknown values are ignored */
    val color: String? = null,
    /** Ask before EVERY run, whatever was approved earlier */
    val confirm: Boolean = false,
    /** Never start a second copy while the first one runs */
    val singleton: Boolean = false,
    /** The line goes to a shell, metacharacters included */
    val shell: Boolean = false,
    val terminal: Terminal = Terminal.INTEGRATED,
    /** A VibeIDE workflow to run instead of the command; we have no workflows and refuse to run the command in its place */
    val workflowId: String? = null,
  ) {
    /** Everything a substitution may appear in */
    private val texts: List<String> get() = listOf(command) + args + listOfNotNull(cwd) + env.values

    /** Secret names this command needs; values are never part of the model. */
    val secretNames: List<String> get() = texts.flatMap { com.vibe.agent.security.SecretRefs.names(it) }.distinct()

    /** The line as a person reads it, references unresolved — safe for a dialog, the feed and the audit log */
    val line: String get() = (listOf(command) + args).joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
  }

  /**
   * @property problems entries that were refused, each as `reason:id`
   * @property notes what we accepted and VibeIDE would not: the file still works here and silently does not there
   */
  data class Parsed(val commands: List<Command>, val problems: List<String>, val notes: List<String> = emptyList())

  fun parse(text: String): Parsed {
    val problems = ArrayList<String>()
    val notes = ArrayList<String>()
    val root = runCatching { Json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text)) }.getOrNull()
    val array = when (root) {
      is JsonObject -> {
        if ((root[VIBE_VERSION] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrEmpty()) notes.add(NOTE_NO_VERSION)
        root["commands"] as? JsonArray
      }
      is JsonArray -> root.also { notes.add(NOTE_BARE_ARRAY) }
      else -> null
    } ?: return Parsed(emptyList(), listOf(PROBLEM_NOT_A_LIST))
    val seen = HashSet<String>()
    val commands = ArrayList<Command>()
    for (element in array) {
      if (commands.size >= MAX_COMMANDS) { problems.add(PROBLEM_TOO_MANY); break }
      val entry = element as? JsonObject ?: run { problems.add(PROBLEM_NOT_AN_OBJECT); continue }
      val id = entry.string("id")?.trim().orEmpty()
      val command = try {
        commandOf(entry, id)
      }
      catch (refused: Refused) {
        problems.add(if (id.isEmpty()) refused.reason else refused.reason + ":" + id)
        continue
      }
      if (!seen.add(id)) { problems.add("$PROBLEM_DUPLICATE:$id"); continue }
      if (entry.string("name").isNullOrEmpty()) notes.add("$NOTE_NO_NAME:$id")
      command.env.keys.filter { SECRET_NAME.containsMatchIn(it) && !hasReference(command.env.getValue(it)) }
        .forEach { notes.add("$NOTE_SECRET_IN_ENV:$id.$it") }
      commands.add(command)
    }
    return Parsed(sorted(commands), problems, notes)
  }

  /** VibeIDE's order: `order` ascending with the commands that have none last, then by name */
  fun sorted(commands: List<Command>): List<Command> =
    commands.sortedWith(compareBy<Command> { it.order ?: Double.MAX_VALUE }.thenBy { it.name })

  private class Refused(val reason: String) : Exception(reason)

  private fun commandOf(entry: JsonObject, id: String): Command {
    val line = entry.string("command")?.trim().orEmpty()
    if (id.isEmpty() || line.isEmpty()) throw Refused(PROBLEM_NO_ID_OR_COMMAND)
    if (!ID.matches(id)) throw Refused(PROBLEM_ID)
    val shell = entry.flag("shell")
    val declaredArgs = entry["args"]?.let { el ->
      (el as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw Refused(PROBLEM_ARGS) }
      ?: throw Refused(PROBLEM_ARGS)
    }
    // Our older spelling keeps the whole line in `command`; with `args` written, `command` is the program as it stands
    val words = if (declaredArgs == null && !shell) splitLine(line) else listOf(line)
    val program = words.first()
    val args = declaredArgs ?: words.drop(1)
    val cwd = entry.string("cwd")?.trim()?.ifEmpty { null }
    val env = entry["env"]?.let { el ->
      (el as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Refused(PROBLEM_ENV) }
      ?: throw Refused(PROBLEM_ENV)
    }.orEmpty()
    val terminal = entry.string("terminal")?.let { Terminal.parse(it) ?: throw Refused(PROBLEM_TERMINAL) } ?: Terminal.INTEGRATED
    (listOf(program) + args + listOfNotNull(cwd) + env.keys + env.values).forEach { hiddenReason(it)?.let { why -> throw Refused(why) } }
    if (!shell && (listOf(program) + args).any { hasMetacharacters(it) }) throw Refused(PROBLEM_METACHARACTERS)
    if (cwd != null && cwd.replace('\\', '/').split('/').any { it == ".." }) throw Refused(PROBLEM_CWD)
    return Command(
      id = id,
      name = (entry.string("name") ?: entry.string("title"))?.trim()?.ifEmpty { null } ?: id,
      command = program,
      args = args,
      description = entry.string("description")?.trim()?.ifEmpty { null },
      cwd = cwd,
      env = env,
      pinned = entry.flag("pinned"),
      order = (entry["order"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull,
      color = entry.string("color"),
      confirm = entry.flag("confirm"),
      singleton = entry.flag("singleton"),
      shell = shell,
      terminal = terminal,
      workflowId = entry.string("workflowId")?.trim()?.ifEmpty { null },
    )
  }

  private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

  /** A line split into words the way a person reads it: whitespace separates, double and single quotes keep a word together */
  fun splitLine(line: String): List<String> {
    val words = ArrayList<String>()
    val word = StringBuilder()
    var quote: Char? = null
    var started = false
    for (c in line) {
      when {
        quote != null -> if (c == quote) quote = null else word.append(c)
        c == '"' || c == '\'' -> { quote = c; started = true }
        c.isWhitespace() -> if (started) { words.add(word.toString()); word.clear(); started = false }
        else -> { word.append(c); started = true }
      }
    }
    if (started) words.add(word.toString())
    return words.ifEmpty { listOf(line) }
  }

  /** Why this text may not be part of a command, or null: characters that hide what is being run */
  private fun hiddenReason(text: String): String? = when {
    text.any { isInvisible(it) } -> PROBLEM_INVISIBLE
    text.any { it.code < 0x20 && it != '\t' || it.code == 0x7F } -> PROBLEM_CONTROL
    else -> null
  }

  /** Zero-width characters, the soft hyphen and the overrides that change the direction a line is read in */
  private fun isInvisible(c: Char): Boolean = when (c.code) {
    0x00AD, 0xFEFF -> true
    in 0x200B..0x200F -> true
    in 0x202A..0x202E -> true
    in 0x2060..0x2069 -> true
    else -> false
  }

  /** Characters that turn one command into several; a reference `${secret:KEY}` is ours and does not count */
  private fun hasMetacharacters(text: String): Boolean = REFERENCE.replace(text, "").any { it in METACHARACTERS }

  private fun hasReference(text: String): Boolean = REFERENCE.containsMatchIn(text)

  /**
   * The approval is over everything that decides what runs: the program, the arguments, the folder, the environment
   * and whether a shell reads the line. Editing any of them revokes it, which is the point; a new title does not.
   */
  fun approvalHash(command: Command): String {
    val shape = listOf(
      command.id,
      command.command,
      command.args.joinToString(FIELD_SEPARATOR),
      command.cwd.orEmpty(),
      command.env.toSortedMap().entries.joinToString(FIELD_SEPARATOR) { "${it.key}=${it.value}" },
      if (command.shell) "1" else "0",
    ).joinToString(RECORD_SEPARATOR)
    val bytes = MessageDigest.getInstance("SHA-256").digest(shape.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
  }

  /** A command with its references replaced, and the names nothing was found for */
  data class Resolved(val command: String, val args: List<String>, val cwd: String?, val env: Map<String, String>, val missing: List<String>)

  /**
   * Substitutes `${secret:KEY}` and `${env:NAME}` for running. A name with no value is REPORTED rather than
   * replaced with emptiness: an empty token turns the command into a request that fails in a confusing way.
   */
  fun resolve(command: Command, secret: (String) -> String?, environment: (String) -> String?): Resolved {
    val missing = LinkedHashSet<String>()
    fun fill(text: String): String = REFERENCE.replace(text) { match ->
      val (kind, name) = match.destructured
      (if (kind == KIND_SECRET) secret(name) else environment(name)) ?: match.value.also { missing.add(it) }
    }
    return Resolved(fill(command.command), command.args.map(::fill), command.cwd?.let(::fill),
                    command.env.mapValues { fill(it.value) }, missing.toList())
  }

  /**
   * The key of our palette for a colour name from the file, null for a name we do not know
   * VibeIDE writes theme colour names (`terminal.ansiBlue`, `charts.red`); the last word is the colour, bright or not
   */
  fun colorKey(name: String?): String? {
    val word = name?.trim()?.substringAfterLast('.')?.removePrefix("ansi")?.removePrefix("Bright")?.lowercase() ?: return null
    return word.takeIf { it in COLOR_KEYS }
  }

  val COLOR_KEYS: Set<String> = setOf("red", "green", "yellow", "blue", "magenta", "cyan", "orange", "purple")

  const val PROBLEM_NOT_A_LIST = "not-a-list"
  const val PROBLEM_NOT_AN_OBJECT = "not-an-object"
  const val PROBLEM_NO_ID_OR_COMMAND = "no-id-or-command"
  const val PROBLEM_ID = "id-invalid"
  const val PROBLEM_ARGS = "args-invalid"
  const val PROBLEM_ENV = "env-invalid"
  const val PROBLEM_TERMINAL = "terminal-invalid"
  const val PROBLEM_CWD = "cwd-traversal"
  const val PROBLEM_DUPLICATE = "duplicate"
  const val PROBLEM_METACHARACTERS = "shell-metacharacters"
  const val PROBLEM_INVISIBLE = "invisible-characters"
  const val PROBLEM_CONTROL = "control-characters"
  const val PROBLEM_TOO_MANY = "too-many"

  const val NOTE_NO_VERSION = "vibeVersion-missing"
  const val NOTE_BARE_ARRAY = "bare-array"
  const val NOTE_NO_NAME = "name-missing"
  const val NOTE_SECRET_IN_ENV = "secret-in-env"

  private const val VIBE_VERSION = "vibeVersion"
  private const val KIND_SECRET = "secret"
  private const val FIELD_SEPARATOR = "\u001f"
  private const val RECORD_SEPARATOR = "\u001e"
  private const val HASH_CHARS = 32

  /** VibeIDE's id: lower-case latin, digits and hyphens, up to 64 characters */
  private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

  /** The two interpolations we perform; a name is not an expression */
  private val REFERENCE = Regex("\\$\\{(secret|env):([A-Za-z0-9_]{1,64})}")

  /** VibeIDE's set: without `shell: true` any of these means the argument is trying to be more than an argument */
  private const val METACHARACTERS = ";&|`$<>(){}*?[]!\\"

  /** Environment names that promise a credential; a literal value under one is a secret written into the repository */
  private val SECRET_NAME = Regex("secret|token|password|passwd|api_?key|private_key|access_key|auth_key|bearer", RegexOption.IGNORE_CASE)
}
