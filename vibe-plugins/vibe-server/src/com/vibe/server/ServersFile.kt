// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path

data class ServerEntry(
  val id: String,
  val name: String = id,
  val kind: String = "service",            // service | task
  val active: Boolean = true,
  val command: String,
  val dir: String? = null,
  val env: Map<String, String> = emptyMap(),
  val envFile: String? = null,
  val pathPrepend: List<String> = emptyList(),
  val port: Int? = null,
  val readyCheck: String? = null,          // port | http | log | exit | spawn (default: port for service, exit for task)
  val readyPath: String = "/",
  val readyPattern: String? = null,
  val readyTimeoutMs: Long = 60_000,
  val dependsOn: List<String> = emptyList(),
  val skipIf: String? = null,
  val autoStart: Boolean = false,
  val previewPath: String? = null,
  val stopCommand: String? = null,
  val note: String? = null,
) {
  val effectiveReadyCheck: String get() = readyCheck ?: if (kind == "task") "exit" else "port"
}

/**
 * `.vibe/servers.json` — the VibeIDE contract: JSONC, {version, servers[]},
 * a malformed entry is skipped with a warning; a top-level problem disables the
 * whole file; duplicate id — the entry is skipped; unknown dependsOn or a
 * dependency cycle EXCLUDES the entry with a reason (never a silent start).
 */
object ServersFile {
  private val json = Json { ignoreUnknownKeys = true }

  fun path(projectBase: String): Path = Path.of(projectBase, ".vibe", "servers.json")

  fun load(projectBase: String?, onWarning: (String) -> Unit): List<ServerEntry> {
    if (projectBase == null) return emptyList()
    val file = path(projectBase)
    if (!Files.isRegularFile(file)) return emptyList()
    val result = ArrayList<ServerEntry>()
    val seen = HashSet<String>()
    try {
      val root = json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(Files.readString(file))).jsonObject
      for (el in root["servers"]?.jsonArray ?: run { onWarning(t("servers.warn.noArray")); return emptyList() }) {
        // Выключенная запись молчит обо всём: она не запустится, и претензии к её содержимому
        // предъявлять некому. Признак снимается сразу после разбора объекта — раньше любой
        // придирки, включая обязательную команду, которая бросает исключение прямо в конструкторе.
        var silent = false
        try {
          val o = el.jsonObject
          // Запись, адресованная другому продукту, пропускается МОЛЧА: набор общий, и запись
          // для соседа — не проблема этой сборки. Проверка стоит раньше всех остальных,
          // включая активность: у чужой записи могут быть поля, которых мы не знаем.
          if (!com.vibe.agent.defaults.VibeProducts.addressedToUs(o)) continue
          silent = o["active"]?.jsonPrimitive?.booleanOrNull == false
          val id = o["id"]?.jsonPrimitive?.contentOrNull
          if (id.isNullOrBlank()) { if (!silent) onWarning(t("servers.warn.noId")); continue }
          if (!seen.add(id)) { if (!silent) onWarning(t("servers.warn.duplicateId", "id" to id)); continue }
          result.add(ServerEntry(
            id = id,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
            kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "service",
            active = !silent,
            command = o["command"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
              ?: throw IllegalArgumentException(t("servers.warn.noCommand")),
            dir = o["dir"]?.jsonPrimitive?.contentOrNull,
            env = o["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            envFile = o["envFile"]?.jsonPrimitive?.contentOrNull,
            pathPrepend = o["pathPrepend"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            port = o["port"]?.jsonPrimitive?.intOrNull,
            readyCheck = o["readyCheck"]?.jsonPrimitive?.contentOrNull,
            readyPath = o["readyPath"]?.jsonPrimitive?.contentOrNull ?: "/",
            readyPattern = o["readyPattern"]?.jsonPrimitive?.contentOrNull,
            readyTimeoutMs = o["readyTimeoutMs"]?.jsonPrimitive?.longOrNull ?: 60_000,
            dependsOn = o["dependsOn"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            skipIf = o["skipIf"]?.jsonPrimitive?.contentOrNull,
            autoStart = o["autoStart"]?.jsonPrimitive?.booleanOrNull ?: false,
            previewPath = o["previewPath"]?.jsonPrimitive?.contentOrNull,
            stopCommand = o["stopCommand"]?.jsonPrimitive?.contentOrNull,
            note = o["note"]?.jsonPrimitive?.contentOrNull,
          ))
        }
        catch (e: Exception) {
          if (!silent) onWarning(t("servers.warn.entrySkipped", "reason" to e.message))
        }
      }
    }
    catch (e: Exception) {
      onWarning(t("servers.warn.parseFailed", "reason" to e.message))
      return emptyList()
    }
    return result.filter { it.active }
  }

  /**
   * Start order as waves (toposort by dependsOn). Entries with unknown deps or in a
   * cycle are returned in [excluded] with a reason and never started silently.
   */
  fun planStartOrder(entries: List<ServerEntry>): Pair<List<List<ServerEntry>>, Map<String, String>> {
    val byId = entries.associateBy { it.id }
    val excluded = LinkedHashMap<String, String>()
    for (e in entries) {
      e.dependsOn.firstOrNull { it !in byId }?.let { excluded[e.id] = t("servers.warn.unknownDependency", "id" to it) }
    }
    // transitively exclude dependents of excluded entries, then detect cycles by wave exhaustion
    var changed = true
    while (changed) {
      changed = false
      for (e in entries) {
        if (e.id in excluded) continue
        val bad = e.dependsOn.firstOrNull { it in excluded }
        if (bad != null) { excluded[e.id] = t("servers.warn.excludedDependency", "id" to bad); changed = true }
      }
    }
    val remaining = entries.filter { it.id !in excluded }.toMutableList()
    val done = HashSet<String>()
    val waves = ArrayList<List<ServerEntry>>()
    while (remaining.isNotEmpty()) {
      val wave = remaining.filter { e -> e.dependsOn.all { it in done } }
      if (wave.isEmpty()) {
        remaining.forEach { excluded[it.id] = t("servers.warn.cycle") }
        break
      }
      waves.add(wave)
      wave.forEach { done.add(it.id); remaining.remove(it) }
    }
    return waves to excluded
  }

  /** Target + transitive dependsOn (for single-entry start). */
  fun selectWithDependencies(entries: List<ServerEntry>, id: String): List<ServerEntry> {
    val byId = entries.associateBy { it.id }
    val picked = LinkedHashSet<String>()
    fun visit(cur: String) {
      if (!picked.add(cur)) return
      byId[cur]?.dependsOn?.forEach { visit(it) }
    }
    visit(id)
    return entries.filter { it.id in picked }
  }
}
