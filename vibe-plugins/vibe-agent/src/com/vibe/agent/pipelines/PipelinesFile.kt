// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.providers.ProvidersFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

data class PipelineStep(
  val role: String,
  val task: String,
  val acceptance: String? = null,
  val maxTokens: Int? = null,
  val maxSteps: Int? = null,
  val continueOnFailure: Boolean = false,
  val ignorePreviousArtifacts: Boolean = false,
)

data class Pipeline(
  val id: String,
  val name: String = id,
  val description: String? = null,
  val steps: List<PipelineStep>,
)

/**
 * `.vibe/pipelines.json` — the VibeIDE contract: version+pipelines[], roles are a
 * closed list, 1..20 steps, a broken pipeline is skipped with a warning shown
 * BEFORE the list (a vanished pipeline must not look merely absent), the rest work.
 * Duplicate id: the second entry is skipped, the first stays.
 */
object PipelinesFile {
  val ROLES: Set<String> = setOf(
    "explore", "implement-step", "recover-or-skip", "orchestrator", "planner", "designer",
    "frontend-dev", "backend-dev", "code-reviewer", "qa", "security",
  )
  private const val MAX_STEPS = 20
  private val json = Json { ignoreUnknownKeys = true }

  fun path(projectBase: String): Path = Path.of(projectBase, ".vibe", "pipelines.json")

  fun load(projectBase: String?, onWarning: (String) -> Unit): List<Pipeline> {
    if (projectBase == null) return emptyList()
    val file = path(projectBase)
    if (!Files.isRegularFile(file)) return emptyList()
    val result = ArrayList<Pipeline>()
    val seen = HashSet<String>()
    try {
      val root = json.parseToJsonElement(ProvidersFile.stripJsonc(Files.readString(file))).jsonObject
      for (el in root["pipelines"]?.jsonArray ?: return emptyList()) {
        try {
          val o = el.jsonObject
          val id = o["id"]?.jsonPrimitive?.contentOrNull
          if (id.isNullOrBlank()) { onWarning("pipelines.json: пайплайн без id пропущен"); continue }
          if (!seen.add(id)) { onWarning("pipelines.json: дубль id '$id' — вторая запись пропущена"); continue }
          val steps = o["steps"]?.jsonArray?.map { s ->
            val so = s.jsonObject
            val role = so["role"]?.jsonPrimitive?.contentOrNull
              ?: throw IllegalArgumentException("шаг без role")
            if (role !in ROLES) throw IllegalArgumentException("неизвестная роль '$role'; доступны: ${ROLES.joinToString()}")
            PipelineStep(
              role = role,
              task = so["task"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                ?: throw IllegalArgumentException("шаг без task"),
              acceptance = so["acceptance"]?.jsonPrimitive?.contentOrNull,
              maxTokens = so["maxTokens"]?.jsonPrimitive?.intOrNull,
              maxSteps = so["maxSteps"]?.jsonPrimitive?.intOrNull,
              continueOnFailure = so["continueOnFailure"]?.jsonPrimitive?.booleanOrNull ?: false,
              ignorePreviousArtifacts = so["ignorePreviousArtifacts"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
          } ?: emptyList()
          if (steps.isEmpty()) { onWarning("pipelines.json: '$id' без шагов — пропущен"); continue }
          if (steps.size > MAX_STEPS) { onWarning("pipelines.json: '$id' длиннее $MAX_STEPS шагов — пропущен"); continue }
          result.add(Pipeline(
            id = id,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
            description = o["description"]?.jsonPrimitive?.contentOrNull,
            steps = steps,
          ))
        }
        catch (e: Exception) {
          onWarning("pipelines.json: пайплайн пропущен: ${e.message}")
        }
      }
    }
    catch (e: Exception) {
      onWarning("pipelines.json не разобран: ${e.message}")
    }
    return result
  }

  /** Role preamble; read-only roles get an explicit no-edit instruction (ACP cannot enforce tools per role — honest deviation from VibeIDE, permissions still gate writes). */
  fun rolePreamble(role: String): String = when (role) {
    "explore" -> "Ты — разведчик. Изучи кодовую базу по задаче. НЕ изменяй файлы — только чтение и анализ."
    "planner" -> "Ты — планировщик. Составь план работ. НЕ изменяй код, кроме файла плана, если он прямо указан в задаче."
    "designer" -> "Ты — дизайнер. Проработай дизайн/интерфейсную часть задачи."
    "frontend-dev" -> "Ты — фронтенд-разработчик. Реализуй интерфейсную часть."
    "backend-dev" -> "Ты — бэкенд-разработчик. Реализуй серверную/логическую часть."
    "implement-step" -> "Ты — исполнитель шага. Сделай ровно то, что сказано в задаче."
    "recover-or-skip" -> "Ты — восстановитель. Оцени состояние после предыдущего шага: почини или явно скажи, что чинить нечего."
    "orchestrator" -> "Ты — оркестратор. Разбей работу и выполни её последовательно."
    "code-reviewer" -> "Ты — ревьюер. Найди дефекты. НЕ изменяй файлы — только отчёт."
    "qa" -> "Ты — QA. Проверь работу по критериям. НЕ изменяй файлы — только отчёт с фактами."
    "security" -> "Ты — безопасник. Проверь изменения на уязвимости. НЕ изменяй файлы — только отчёт."
    else -> ""
  }
}
