// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Свои MCP-серверы человека — `.vibe/mcp.json`.
 *
 * Чего не хватало: инструменты у агента были только НАШИ (индексы IDE) и общей памяти. Подключить
 * свой сервер — трекер задач, базу, чужой API — было нельзя ни прямому чату, ни ACP-агенту, хотя
 * весь протокол для этого у нас уже написан и работает в обе стороны. Это ровно та дыра, из-за
 * которой «агент в IDE» отличается от «агента в редакторе рядом» не в нашу пользу.
 *
 * Формат намеренно ТОТ ЖЕ, что у Claude Desktop и у VibeIDE: `{"mcpServers": {"имя": {"command",
 * "args", "env", "disabled"}}}`. Свой формат заставил бы человека переписывать то, что у него уже
 * лежит в другом инструменте, — и это единственное, что он о нас запомнил бы.
 *
 * Разбор терпимый и ГРОМКИЙ: битая запись называется по имени и пропускается, остальные работают.
 * Молчаливо пропущенный сервер выглядит как сломанный инструмент, а не как опечатка в конфиге.
 *
 * Two files, one format: the shared `~/.vibe/mcp.json` (the person's servers for every project) and the project's
 * `.vibe/mcp.json`, which is stronger: an entry of the same name replaces the shared one whole, never field by field
 * Names compare exactly as written
 * A broken file of one layer does not switch the other off: the complaint names the file, the other layer's servers run
 * The parse and the layers are pinned to VibeIDE by the shared vectors (`testVectors/mcpServersFile.json`)
 */
object McpServersFile {
  const val FILE_NAME = "mcp.json"
  const val DIR_NAME = ".vibe"

  /** Which of the two files; the values go from the weaker to the stronger */
  enum class Layer(
    /** How a complaint and a hint name the file: a path rather than a phrase, so it is not in the string catalog */
    val fileLabel: String,
  ) {
    USER("~/$DIR_NAME/$FILE_NAME"),
    PROJECT("$DIR_NAME/$FILE_NAME"),
  }

  data class Entry(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** Выключенный сервер остаётся в файле как напоминание, но не запускается. */
    val disabled: Boolean = false,
    /** Working folder of the server process, a path as written; null means the project folder */
    val cwd: String? = null,
  ) {
    /** Where the process of this server runs: its own `cwd` as written, otherwise [fallback] */
    fun processDir(fallback: Path?): Path? = cwd?.let { Path.of(it) } ?: fallback
  }

  /**
   * Что не так с файлом — КОДОМ и именем, а не фразой.
   *
   * Разбор ничего не знает о языке интерфейса: фразу собирает панель. Иначе жалоба парсера
   * навсегда остаётся на том языке, на котором её однажды написали, и половина сообщений IDE
   * говорит по-русски, а половина по-английски (общее правило локализации).
   */
  enum class Problem { NOT_JSON, NO_SECTION, NOT_AN_OBJECT, NO_COMMAND, AMBIGUOUS, UNREADABLE }

  data class Complaint(val problem: Problem, val name: String? = null)

  /**
   * One file read: the servers to run and the complaints
   *
   * [remote] are the names of entries with `url` and no `command`: servers to connect to by address, which this product
   * does not run. They are no complaint, but they are in the file, so a stronger layer's entry shadows a weaker one by name
   */
  data class Parsed(val servers: List<Entry>, val problems: List<Complaint>, val remote: List<String> = emptyList())

  val EMPTY = Parsed(emptyList(), emptyList())

  /** Both files laid one over the other: what runs, where each name comes from, what each file complained of */
  data class Merged(
    val servers: List<Entry>,
    val remote: List<String>,
    val sourceOfName: Map<String, Layer>,
    /** Names of the project file whose entry replaced an entry of the same name in the shared file */
    val overrides: Set<String>,
    val problems: Map<Layer, List<Complaint>>,
    /** The names each file holds, remote and run alike: a new entry must not repeat one of them */
    val names: Map<Layer, Set<String>>,
  ) {
    /** The complaints in the order the files are read, each with the file it is about */
    val allProblems: List<Pair<Layer, Complaint>>
      get() = Layer.entries.flatMap { layer -> problems[layer].orEmpty().map { layer to it } }
  }

  /** Path of the shared file; [home] is the person's home folder */
  fun userPath(home: String = System.getProperty("user.home")): Path = Path.of(home, DIR_NAME, FILE_NAME)

  /** Path of the project file; null — the project has no root */
  fun pathIn(projectBase: String?): Path? = projectBase?.let { Path.of(it, DIR_NAME, FILE_NAME) }

  /** Path of the file of [layer] */
  fun pathOf(layer: Layer, projectBase: String?, home: String = System.getProperty("user.home")): Path? =
    when (layer) {
      Layer.USER -> userPath(home)
      Layer.PROJECT -> pathIn(projectBase)
    }

  /** Both files, read and laid one over the other; the single place that reads the files of servers from disk */
  fun loadLayers(projectBase: String?, home: String = System.getProperty("user.home")): Merged =
    merge(load(userPath(home)), load(pathIn(projectBase)))

  private fun load(path: Path?): Parsed {
    if (path == null || !Files.isRegularFile(path)) return EMPTY
    val text = runCatching { Files.readString(path) }.getOrElse { return Parsed(emptyList(), listOf(Complaint(Problem.UNREADABLE))) }
    return parse(text)
  }

  /**
   * The project file over the shared one: an entry replaces the entry of the same name whole, names compare as written
   *
   * A name keeps the place it first had, so the shared file's servers come first and a replaced one stays where it stood
   */
  fun merge(user: Parsed, project: Parsed): Merged {
    val servers = LinkedHashMap<String, Entry>()
    val remote = LinkedHashSet<String>()
    val sourceOfName = LinkedHashMap<String, Layer>()
    val overrides = LinkedHashSet<String>()
    for ((layer, parsed) in listOf(Layer.USER to user, Layer.PROJECT to project)) {
      // A name is one server whatever its kind: an entry by address and an entry with a command shadow each other
      val own = LinkedHashMap<String, Entry?>()
      parsed.servers.forEach { own[it.name] = it }
      parsed.remote.forEach { own[it] = null }
      for ((name, entry) in own) {
        if (name in sourceOfName) overrides += name
        sourceOfName[name] = layer
        if (entry != null) {
          remote.remove(name)
          servers[name] = entry
        }
        else {
          servers.remove(name)
          remote += name
        }
      }
    }
    return Merged(
      servers.values.toList(), remote.toList(), sourceOfName, overrides,
      mapOf(Layer.USER to user.problems, Layer.PROJECT to project.problems),
      mapOf(Layer.USER to namesOf(user), Layer.PROJECT to namesOf(project)),
    )
  }

  private fun namesOf(parsed: Parsed): Set<String> = parsed.servers.mapTo(LinkedHashSet()) { it.name } + parsed.remote

  /** Чистый разбор: файл текстом внутрь, записи и жалобы наружу. */
  fun parse(text: String): Parsed {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
      ?: return Parsed(emptyList(), listOf(Complaint(Problem.NOT_JSON)))
    val servers = (root["mcpServers"] as? JsonObject)
      ?: return Parsed(emptyList(), listOf(Complaint(Problem.NO_SECTION)))
    val entries = ArrayList<Entry>()
    val remote = ArrayList<String>()
    val problems = ArrayList<Complaint>()
    for ((name, element) in servers) {
      val body = element as? JsonObject
      if (body == null) {
        problems += Complaint(Problem.NOT_AN_OBJECT, name)
        continue
      }
      val command = stringOf(body["command"])?.trim().orEmpty()
      val hasUrl = stringOf(body["url"])?.isNotBlank() == true
      when {
        // Two products would start different things from one record: one runs the program, the other opens the address
        command.isNotEmpty() && hasUrl -> problems += Complaint(Problem.AMBIGUOUS, name)
        command.isNotEmpty() -> entries += Entry(
          name = name,
          command = command,
          args = (body["args"] as? JsonArray)?.mapNotNull { scalarText(it) } ?: emptyList(),
          env = (body["env"] as? JsonObject)?.mapNotNull { (key, value) -> scalarText(value)?.let { key to it } }?.toMap() ?: emptyMap(),
          disabled = scalarText(body["disabled"]) == "true",
          cwd = stringOf(body["cwd"])?.takeIf { it.isNotBlank() },
        )
        // A server by address belongs to the product that connects to addresses; the others pass it by without a word
        hasUrl -> remote += name
        // Именно НАЗВАТЬ: сервер без команды нечем запускать, и человек должен узнать про какой
        // именно из пяти идёт речь.
        else -> problems += Complaint(Problem.NO_COMMAND, name)
      }
    }
    return Parsed(entries, problems, remote)
  }

  /** A JSON string as text; a number, a boolean or anything else is not a string here */
  private fun stringOf(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content

  /** A scalar as text, as VibeIDE reads it: `5` and `true` are the strings «5» and «true», `null` and a container are nothing */
  private fun scalarText(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** Запись для `session/new` ACP-агента: он запускает сервер сам, поэтому получает команду. */
  fun acpEntry(entry: Entry): Map<String, Any> = buildMap {
    put("type", "stdio")
    put("name", entry.name)
    put("command", entry.command)
    put("args", entry.args)
    // Переменные едут списком пар, как того требует схема v1: объект адаптеры не поймут.
    put("env", entry.env.map { (key, value) -> mapOf("name" to key, "value" to value) })
  }
}
