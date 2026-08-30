// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.learning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Teaching a person, with the progress remembered between sessions.
 *
 * An ordinary chat teaches badly for two reasons, and both are structural. It starts every lesson
 * from zero, so the tenth lesson repeats the first. And it asks nothing before starting, so it
 * teaches an average person an average version of the topic — which is exactly what the internet
 * already does for free.
 *
 * Hence two rules encoded here. A MISSION is a gate, not a form: until it is known why this skill
 * is needed now, what the learner can already do and what a real result looks like, there is no
 * lesson. And DIFFICULTY is chosen by measured outcomes rather than by the model's opinion of its
 * own explanation: a model asked whether it explained well says yes.
 */
object LearningPlan {
  enum class Difficulty { EASY, NORMAL, HARD }

  /** What must be answered before the first lesson. Three questions, deliberately not ten. */
  data class Mission(val why: String, val already: String, val result: String) {
    val isComplete: Boolean get() = why.isNotBlank() && already.isNotBlank() && result.isNotBlank()
  }

  data class Attempt(val lesson: String, val correct: Int, val total: Int, val atMs: Long) {
    val share: Double get() = if (total <= 0) 0.0 else correct.toDouble() / total
  }

  data class Progress(
    val skill: String,
    val mission: Mission?,
    val attempts: List<Attempt> = emptyList(),
    val difficulty: Difficulty = Difficulty.NORMAL,
  ) {
    val lessonsDone: Int get() = attempts.size
    val lastLesson: String? get() = attempts.lastOrNull()?.lesson
  }

  /** Missing pieces of the mission — asked as questions rather than reported as errors. */
  fun missingMissionParts(mission: Mission?): List<String> = buildList {
    if (mission == null || mission.why.isBlank()) add(WHY)
    if (mission == null || mission.already.isBlank()) add(ALREADY)
    if (mission == null || mission.result.isBlank()) add(RESULT)
  }

  /**
   * Difficulty for the next lesson, from the last two attempts.
   *
   * Two rather than one: a single bad attempt is a bad day, and a single good one is luck. Too easy
   * is an illusion of progress, too hard is overload, and both end the same way — the learner stops.
   */
  fun nextDifficulty(current: Difficulty, attempts: List<Attempt>): Difficulty {
    val recent = attempts.takeLast(2)
    if (recent.size < 2) return current
    val average = recent.sumOf { it.share } / recent.size
    return when {
      average >= RAISE_THRESHOLD -> current.harder()
      average <= LOWER_THRESHOLD -> current.easier()
      else -> current
    }
  }

  private fun Difficulty.harder(): Difficulty = when (this) {
    Difficulty.EASY -> Difficulty.NORMAL
    else -> Difficulty.HARD
  }

  private fun Difficulty.easier(): Difficulty = when (this) {
    Difficulty.HARD -> Difficulty.NORMAL
    else -> Difficulty.EASY
  }

  /**
   * The prompt for the next lesson. It carries the mission, the progress and the sources — a lesson
   * taught from the model's memory is a lesson whose errors nobody can find.
   */
  fun lessonPrompt(progress: Progress, resources: String?, labels: Labels): String = buildString {
    appendLine(labels.role)
    appendLine()
    appendLine(labels.skill(progress.skill))
    progress.mission?.let {
      appendLine(labels.mission(it.why, it.already, it.result))
    }
    appendLine(labels.progress(progress.lessonsDone, progress.difficulty.name.lowercase(), progress.lastLesson))
    if (!resources.isNullOrBlank()) {
      appendLine()
      appendLine(labels.sources)
      appendLine(resources.take(MAX_RESOURCES_CHARS))
    }
    else appendLine(labels.noSources)
    appendLine()
    append(labels.format)
  }

  interface Labels {
    val role: String
    val sources: String
    val noSources: String
    val format: String
    fun skill(skill: String): String
    fun mission(why: String, already: String, result: String): String
    fun progress(lessons: Int, difficulty: String, lastLesson: String?): String
  }

  // --- storage ---

  fun encode(progress: Progress): JsonObject = buildJsonObject {
    put("skill", progress.skill)
    put("difficulty", progress.difficulty.name.lowercase())
    progress.mission?.let { mission ->
      put("mission", buildJsonObject {
        put("why", mission.why)
        put("already", mission.already)
        put("result", mission.result)
      })
    }
    put("attempts", JsonArray(progress.attempts.map { attempt ->
      buildJsonObject {
        put("lesson", attempt.lesson)
        put("correct", attempt.correct)
        put("total", attempt.total)
        put("at", attempt.atMs)
      }
    }))
  }

  fun decode(json: JsonObject): Progress {
    val mission = (json["mission"] as? JsonObject)?.let {
      Mission(
        why = it["why"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        already = it["already"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        result = it["result"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      )
    }
    val attempts = (json["attempts"] as? JsonArray).orEmpty().mapNotNull { element ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      Attempt(
        lesson = entry["lesson"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
        correct = entry["correct"]?.jsonPrimitive?.intOrNull ?: 0,
        total = entry["total"]?.jsonPrimitive?.intOrNull ?: 0,
        atMs = entry["at"]?.jsonPrimitive?.longOrNull ?: 0,
      )
    }
    return Progress(
      skill = json["skill"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      mission = mission,
      attempts = attempts,
      difficulty = difficultyOf(json["difficulty"]?.jsonPrimitive?.contentOrNull),
    )
  }

  fun difficultyOf(name: String?): Difficulty = when (name?.lowercase()) {
    "easy" -> Difficulty.EASY
    "hard" -> Difficulty.HARD
    else -> Difficulty.NORMAL
  }

  const val WHY = "why"
  const val ALREADY = "already"
  const val RESULT = "result"

  /** Two good attempts in a row raise the bar; two weak ones lower it. */
  const val RAISE_THRESHOLD = 0.85
  const val LOWER_THRESHOLD = 0.5

  private const val MAX_RESOURCES_CHARS = 20_000
}
