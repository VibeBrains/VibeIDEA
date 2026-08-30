// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearningPlanTest {
  private val labels = object : LearningPlan.Labels {
    override val role = "ты учитель"
    override val sources = "источники:"
    override val noSources = "источников нет"
    override val format = "формат урока"
    override fun skill(skill: String) = "навык: $skill"
    override fun mission(why: String, already: String, result: String) = "миссия: $why / $already / $result"
    override fun progress(lessons: Int, difficulty: String, lastLesson: String?) =
      "уроков $lessons, сложность $difficulty, прошлый ${lastLesson ?: "-"}"
  }

  private fun attempt(share: Double, lesson: String = "урок") =
    LearningPlan.Attempt(lesson, correct = (share * 10).toInt(), total = 10, atMs = 0)

  @Test
  fun `the mission is a gate — what is missing is asked, not assumed`() {
    assertEquals(listOf(LearningPlan.WHY, LearningPlan.ALREADY, LearningPlan.RESULT),
                 LearningPlan.missingMissionParts(null))
    val partial = LearningPlan.Mission(why = "для работы", already = "", result = "")
    assertEquals(listOf(LearningPlan.ALREADY, LearningPlan.RESULT), LearningPlan.missingMissionParts(partial))
  }

  @Test
  fun `a complete mission has nothing missing`() {
    val mission = LearningPlan.Mission("для работы", "пишу на python", "смогу читать чужой Kotlin")
    assertTrue(mission.isComplete)
    assertTrue(LearningPlan.missingMissionParts(mission).isEmpty())
  }

  @Test
  fun `one attempt changes nothing`() {
    // Один плохой раз — это плохой день, один хороший — везение.
    assertEquals(LearningPlan.Difficulty.NORMAL,
                 LearningPlan.nextDifficulty(LearningPlan.Difficulty.NORMAL, listOf(attempt(1.0))))
  }

  @Test
  fun `two strong attempts raise the bar, two weak ones lower it`() {
    assertEquals(LearningPlan.Difficulty.HARD,
                 LearningPlan.nextDifficulty(LearningPlan.Difficulty.NORMAL, listOf(attempt(0.9), attempt(0.9))))
    assertEquals(LearningPlan.Difficulty.EASY,
                 LearningPlan.nextDifficulty(LearningPlan.Difficulty.NORMAL, listOf(attempt(0.3), attempt(0.4))))
  }

  @Test
  fun `the middle keeps the level`() {
    assertEquals(LearningPlan.Difficulty.NORMAL,
                 LearningPlan.nextDifficulty(LearningPlan.Difficulty.NORMAL, listOf(attempt(0.7), attempt(0.6))))
  }

  @Test
  fun `difficulty never runs past its ends`() {
    assertEquals(LearningPlan.Difficulty.HARD,
                 LearningPlan.nextDifficulty(LearningPlan.Difficulty.HARD, listOf(attempt(1.0), attempt(1.0))))
    assertEquals(LearningPlan.Difficulty.EASY,
                 LearningPlan.nextDifficulty(LearningPlan.Difficulty.EASY, listOf(attempt(0.0), attempt(0.1))))
  }

  @Test
  fun `an empty attempt does not count as a perfect score`() {
    // total = 0 не должен читаться как «ответил на всё».
    assertEquals(0.0, LearningPlan.Attempt("урок", correct = 0, total = 0, atMs = 0).share)
  }

  @Test
  fun `the lesson prompt carries the mission, the progress and the sources`() {
    val progress = LearningPlan.Progress(
      skill = "kotlin",
      mission = LearningPlan.Mission("для работы", "знаю python", "прочитаю чужой код"),
      attempts = listOf(attempt(0.9, "первый")),
    )
    val text = LearningPlan.lessonPrompt(progress, "конспект из RESOURCES.md", labels)
    assertTrue(text.contains("навык: kotlin"))
    assertTrue(text.contains("миссия: для работы"))
    assertTrue(text.contains("уроков 1"))
    assertTrue(text.contains("конспект из RESOURCES.md"))
  }

  @Test
  fun `without sources the prompt says so instead of pretending`() {
    // Урок из памяти модели — урок, ошибки которого никто не найдёт.
    val text = LearningPlan.lessonPrompt(LearningPlan.Progress("kotlin", null), null, labels)
    assertTrue(text.contains("источников нет"))
  }

  @Test
  fun `progress survives a round trip`() {
    val progress = LearningPlan.Progress(
      skill = "kotlin",
      mission = LearningPlan.Mission("a", "b", "c"),
      attempts = listOf(LearningPlan.Attempt("урок", 8, 10, 123)),
      difficulty = LearningPlan.Difficulty.HARD,
    )
    val restored = LearningPlan.decode(LearningPlan.encode(progress))
    assertEquals(progress.skill, restored.skill)
    assertEquals(progress.mission, restored.mission)
    assertEquals(progress.attempts, restored.attempts)
    assertEquals(LearningPlan.Difficulty.HARD, restored.difficulty)
  }

  @Test
  fun `an unknown difficulty reads as normal rather than failing`() {
    assertEquals(LearningPlan.Difficulty.NORMAL, LearningPlan.difficultyOf("что-то"))
    assertEquals(LearningPlan.Difficulty.NORMAL, LearningPlan.difficultyOf(null))
  }
}
