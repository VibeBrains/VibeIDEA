// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.providers.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Прогон случаев на подделке вместо модели: порядок вызовов, отказы, отчёт. */
class SkillEvalRunTest {
  private val suite = SkillEvals.Suite("deploy", null, listOf(
    SkillEvals.Case("a", "Выложи сервис", expectations = listOf("пробный прогон")),
    SkillEvals.Case("b", "Откати выпуск"),
  ))

  /** Отвечает по очереди из списка; строка, начинающаяся с `!`, бросается как отказ провайдера. */
  private class Fake(private val answers: MutableList<String>) : SkillEvalRun.Caller {
    val seen = ArrayList<List<ChatMessage>>()

    override fun ask(messages: List<ChatMessage>): String {
      seen += messages
      val next = answers.removeFirst()
      if (next.startsWith("!")) throw RuntimeException(next.drop(1))
      return next
    }
  }

  @Test
  fun `each case costs two calls - the model and then the judge`() {
    val fake = Fake(mutableListOf(
      "сначала пробный прогон", """{"passed": true, "failures": []}""",
      "откачу сразу", """{"passed": false, "failures": ["нет пробного прогона"]}""",
    ))
    val report = SkillEvalRun.run("deploy", "gpt-4o", "# deploy", suite, fake, now = { 42L })
    assertEquals(4, fake.seen.size)
    // Первый вызов — навык в системном сообщении, второй — судья со своим сводом правил.
    assertTrue(fake.seen[0][0].text.startsWith("# deploy"))
    assertTrue("JSON only" in fake.seen[1][0].text)
    assertEquals(1, report.passed)
    assertEquals(1, report.failed)
    assertEquals(0, report.unjudged)
    assertEquals(42L, report.finishedAtMs)
  }

  @Test
  fun `a provider refusal keeps the case and does not lose the run`() {
    val fake = Fake(mutableListOf("!429 too many requests", "откачу", """{"passed": true}"""))
    val report = SkillEvalRun.run("deploy", "gpt-4o", "# deploy", suite, fake)
    assertEquals(2, report.results.size)
    assertNull(report.results[0].verdict)
    assertEquals("429 too many requests", report.results[0].error)
    assertTrue(report.results[1].passed)
    assertEquals(1, report.unjudged)
  }

  @Test
  fun `a judge that answers prose leaves the case unjudged, not passed`() {
    val fake = Fake(mutableListOf("ответ", "по-моему всё хорошо", "ответ", "по-моему всё хорошо"))
    val report = SkillEvalRun.run("deploy", "gpt-4o", "# deploy", suite, fake)
    assertEquals(0, report.passed)
    assertEquals(0, report.failed)
    assertEquals(2, report.unjudged)
    assertTrue(report.results[0].error!!.contains("по-моему"))
  }

  @Test
  fun `a stop is honoured between cases`() {
    var done = 0
    val fake = Fake(mutableListOf("ответ", """{"passed": true}""", "ответ", """{"passed": true}"""))
    val report = SkillEvalRun.run("deploy", "gpt-4o", "# deploy", suite, fake,
                                  cancelled = { done >= 1 }, onProgress = { _, _ -> done++ })
    assertEquals(1, report.results.size)
  }

  @Test
  fun `the report carries the verdicts and the answers`() {
    val fake = Fake(mutableListOf(
      "сначала пробный прогон", """{"passed": true, "failures": [], "note": "хорошо"}""",
      "!нет ключа",
    ))
    val json = SkillEvalRun.toJson(SkillEvalRun.run("deploy", "gpt-4o", "# deploy", suite, fake, now = { 7L })).toString()
    assertTrue("\"skill\":\"deploy\"" in json, json)
    assertTrue("\"model\":\"gpt-4o\"" in json)
    assertTrue("\"passed\":1" in json)
    assertTrue("\"unjudged\":1" in json)
    assertTrue("\"note\":\"хорошо\"" in json)
    assertTrue("\"error\":\"нет ключа\"" in json)
  }
}
