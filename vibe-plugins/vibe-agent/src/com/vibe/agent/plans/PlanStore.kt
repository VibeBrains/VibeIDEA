// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.plans

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plans on disk, one per chat thread, in `.vibe/plans.json`.
 *
 * In the project rather than in the IDE's config: a plan describes THIS repository's work, it is
 * worth seeing in a diff, and a colleague opening the branch is better off knowing what was still
 * unfinished. Writes are whole-file and best effort — a plan that fails to save must never take a
 * turn down with it, because the plan is a convenience and the turn is the work.
 */
@Service(Service.Level.PROJECT)
class PlanStore(private val project: Project) {
  private val log = logger<PlanStore>()
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  @Synchronized
  fun save(threadId: String, plan: AgentPlan.Plan) {
    val base = project.basePath ?: return
    runCatching {
      val all = loadAll().toMutableMap()
      // A finished plan is not kept: the file would grow with the history of every chat, and a
      // finished plan answers no question anyone asks later.
      if (plan.isEmpty || plan.isFinished) all.remove(threadId) else all[threadId] = plan
      val trimmed = all.entries.sortedByDescending { it.value.updatedAtMs }.take(MAX_PLANS).associate { it.toPair() }
      val file = Path.of(base, FILE)
      Files.createDirectories(file.parent)
      Files.writeString(file, json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("version", VERSION)
        put("plans", JsonObject(trimmed.mapValues { (_, value) -> AgentPlan.encode(value) }))
      }))
    }.onFailure { log.warn("plans.json could not be written: ${it.message}") }
  }

  @Synchronized
  fun load(threadId: String): AgentPlan.Plan? = loadAll()[threadId]

  @Synchronized
  fun loadAll(): Map<String, AgentPlan.Plan> {
    val base = project.basePath ?: return emptyMap()
    val file = Path.of(base, FILE)
    if (!Files.exists(file)) return emptyMap()
    return runCatching {
      val root = json.parseToJsonElement(Files.readString(file)).jsonObject
      val plans = root["plans"]?.jsonObject ?: return emptyMap()
      plans.mapNotNull { (id, element) ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        id to AgentPlan.decode(obj)
      }.toMap()
    }.getOrElse {
      log.warn("plans.json could not be read: ${it.message}")
      emptyMap()
    }
  }

  companion object {
    const val FILE = ".vibe/plans.json"
    private const val VERSION = 1

    /** Enough for the chats one actually returns to; the rest are history, not work in progress. */
    private const val MAX_PLANS = 20

    fun getInstance(project: Project): PlanStore = project.getService(PlanStore::class.java)
  }
}
