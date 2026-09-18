// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 */
object McpServersFile {
  const val FILE_NAME = "mcp.json"

  data class Entry(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** Выключенный сервер остаётся в файле как напоминание, но не запускается. */
    val disabled: Boolean = false,
  )

  /**
   * Что не так с файлом — КОДОМ и именем, а не фразой.
   *
   * Разбор ничего не знает о языке интерфейса: фразу собирает панель. Иначе жалоба парсера
   * навсегда остаётся на том языке, на котором её однажды написали, и половина сообщений IDE
   * говорит по-русски, а половина по-английски (общее правило локализации).
   */
  enum class Problem { NOT_JSON, NO_SECTION, NOT_AN_OBJECT, NO_COMMAND, UNREADABLE }

  data class Complaint(val problem: Problem, val name: String? = null)

  data class Parsed(val servers: List<Entry>, val problems: List<Complaint>)

  val EMPTY = Parsed(emptyList(), emptyList())

  /** Путь файла в проекте; null — у проекта нет корня. */
  fun pathIn(projectBase: String?): Path? = projectBase?.let { Path.of(it, ".vibe", FILE_NAME) }

  fun load(projectBase: String?): Parsed {
    val path = pathIn(projectBase) ?: return EMPTY
    if (!Files.isRegularFile(path)) return EMPTY
    val text = runCatching { Files.readString(path) }.getOrElse { return Parsed(emptyList(), listOf(Complaint(Problem.UNREADABLE))) }
    return parse(text)
  }

  /** Чистый разбор: файл текстом внутрь, записи и жалобы наружу. */
  fun parse(text: String): Parsed {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
      ?: return Parsed(emptyList(), listOf(Complaint(Problem.NOT_JSON)))
    val servers = (root["mcpServers"] as? JsonObject)
      ?: return Parsed(emptyList(), listOf(Complaint(Problem.NO_SECTION)))
    val entries = ArrayList<Entry>()
    val problems = ArrayList<Complaint>()
    for ((name, element) in servers) {
      val body = element as? JsonObject
      if (body == null) {
        problems += Complaint(Problem.NOT_AN_OBJECT, name)
        continue
      }
      val command = body["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      if (command.isEmpty()) {
        // Именно НАЗВАТЬ: сервер без команды нечем запускать, и человек должен узнать про какой
        // именно из пяти идёт речь.
        problems += Complaint(Problem.NO_COMMAND, name)
        continue
      }
      entries += Entry(
        name = name,
        command = command,
        args = (body["args"] as? kotlinx.serialization.json.JsonArray)?.jsonArray
          ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        env = (body["env"] as? JsonObject)?.mapNotNull { (key, value) ->
          value.jsonPrimitive.contentOrNull?.let { key to it }
        }?.toMap() ?: emptyMap(),
        disabled = body["disabled"]?.jsonPrimitive?.contentOrNull == "true",
      )
    }
    return Parsed(entries, problems)
  }

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
