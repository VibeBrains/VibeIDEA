// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Разбор файла эвалов, оба промпта и чтение вердикта — всё чистое, без сети и диска. */
class SkillEvalsTest {
  private val suiteText = """
    {
      "skill_name": "deploy",
      "description": "Проверки навыка выкладки",
      "evals": [
        {
          "id": "dry-run-first",
          "prompt": "Выложи сервис в стейджинг",
          "files": ["deploy.sh"],
          "expected_output": "Сначала пробный прогон",
          "expectations": ["показывает пробный прогон до выполнения", "не ставит --yes без спроса"]
        },
        { "prompt": "Откати последний выпуск" }
      ]
    }
  """.trimIndent()

  @Test
  fun `a suite is read case by case, and a case without id gets its number`() {
    val read = SkillEvals.read(suiteText)
    assertIs<SkillEvals.Read.Ok>(read)
    val cases = read.suite.cases
    assertEquals("deploy", read.suite.skillName)
    assertEquals(listOf("dry-run-first", "2"), cases.map { it.id })
    assertEquals(listOf("deploy.sh"), cases[0].files)
    assertEquals(2, cases[0].expectations.size)
    assertEquals("Сначала пробный прогон", cases[0].expectedOutput)
    assertTrue(cases[1].expectations.isEmpty())
  }

  @Test
  fun `no file is not a fault, but a broken one is named`() {
    assertIs<SkillEvals.Read.Missing>(SkillEvals.read(null))
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read(""))
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read("{ не json"))
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read("""{"skill_name":"x"}"""))
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read("""{"evals":[]}"""))
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read("""{"evals":[{"id":"a"}]}"""))
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read("""{"evals":[{"id":"a","prompt":"p"},{"id":"a","prompt":"q"}]}"""))
  }

  @Test
  fun `the model under test gets the skill, the prompt and the files — and no tools`() {
    val case = SkillEvals.Case("c1", "Выложи сервис", listOf("deploy.sh"))
    val messages = SkillEvals.runMessages("# deploy\nСначала пробный прогон.", case, mapOf("deploy.sh" to "echo hi"))
    assertEquals(listOf("system", "user"), messages.map { it.role })
    assertTrue(messages[0].text.startsWith("# deploy"))
    assertTrue("no tools in this run" in messages[0].text, messages[0].text)
    assertTrue("<file path=\"deploy.sh\">\necho hi\n</file>" in messages[1].text, messages[1].text)
  }

  @Test
  fun `the judge gets the checklist numbered and the answer clipped`() {
    val case = SkillEvals.Case("c1", "Выложи", expectedOutput = "пробный прогон",
                               expectations = listOf("показывает пробный прогон", "не ставит --yes"))
    val messages = SkillEvals.judgeMessages(case, "x".repeat(SkillEvals.ANSWER_LIMIT + 500))
    assertTrue("JSON only" in messages[0].text)
    val user = messages[1].text
    assertTrue("1. показывает пробный прогон" in user, user)
    assertTrue("2. не ставит --yes" in user, user)
    assertTrue("<expected_output>" in user)
    assertTrue(user.length < SkillEvals.ANSWER_LIMIT + 1000, "ответ судье обрезается")
  }

  @Test
  fun `a verdict is read plain, in fences and among prose`() {
    val plain = SkillEvals.verdictOf("""{"passed": true, "failures": [], "note": "всё на месте"}""")
    assertEquals(SkillEvals.Verdict(true, emptyList(), "всё на месте"), plain)
    val fenced = SkillEvals.verdictOf("```json\n{\"passed\": false, \"failures\": [\"нет пробного прогона\"]}\n```")
    assertEquals(false, fenced?.passed)
    assertEquals(listOf("нет пробного прогона"), fenced?.failures)
    assertEquals(true, SkillEvals.verdictOf("Вот вердикт: {\"passed\": true} — готово")?.passed)
  }

  @Test
  fun `an answer that is not a verdict is not guessed`() {
    // Судья, ответивший прозой, — это не «прошло»: случай пойдёт в отчёт как несудимый.
    assertNull(SkillEvals.verdictOf("По-моему, всё хорошо"))
    assertNull(SkillEvals.verdictOf(""))
    assertNull(SkillEvals.verdictOf("""{"note": "забыл поле passed"}"""))
  }
}
