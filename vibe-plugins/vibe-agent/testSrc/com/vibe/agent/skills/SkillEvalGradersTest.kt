// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.providers.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Free graders, repeated attempts and the no-skill baseline. */
class SkillEvalGradersTest {
  private class Script(private val answers: MutableList<String>) : SkillEvalRun.Caller {
    val seen = ArrayList<List<ChatMessage>>()
    override fun ask(messages: List<ChatMessage>): String { seen += messages; return answers.removeFirst() }
  }

  private fun suite(json: String) = (SkillEvals.read(json) as SkillEvals.Read.Ok).suite

  @Test
  fun `a case made only of assertions never calls the judge`() {
    val s = suite("""{"evals":[{"id":"a","prompt":"выложи","assertions":[{"type":"regex","pattern":"--dry-run"},{"type":"not_regex","pattern":"--yes"}]}]}""")
    val script = Script(mutableListOf("сначала deploy --dry-run"))
    val report = SkillEvalRun.run("d", "m", "# d", s, script)
    assertEquals(1, script.seen.size)
    assertTrue(report.results.single().passed)
    assertEquals(1, SkillEvalRun.calls(s))
  }

  @Test
  fun `a broken assertion fails the attempt and names the pattern`() {
    val s = suite("""{"evals":[{"id":"a","prompt":"выложи","assertions":[{"type":"not_regex","pattern":"--yes"}]}]}""")
    val report = SkillEvalRun.run("d", "m", "# d", s, Script(mutableListOf("deploy --yes")))
    assertFalse(report.results.single().passed)
    assertEquals(listOf("not_regex: --yes"), report.results.single().verdict!!.failures)
  }

  @Test
  fun `assertions bind together with the judge`() {
    val s = suite("""{"evals":[{"id":"a","prompt":"p","expectations":["x"],"assertions":[{"type":"regex","pattern":"dry"}]}]}""")
    val report = SkillEvalRun.run("d", "m", "# d", s, Script(mutableListOf("нет", """{"passed": true, "failures": []}""")))
    assertFalse(report.results.single().passed)
  }

  @Test
  fun `every attempt must pass and the rate is reported`() {
    val s = suite("""{"runs":3,"evals":[{"id":"a","prompt":"p","assertions":[{"type":"regex","pattern":"ok"}]}]}""")
    val report = SkillEvalRun.run("d", "m", "# d", s, Script(mutableListOf("ok", "нет", "ok")))
    val result = report.results.single()
    assertFalse(result.passed)
    assertEquals(2.0 / 3, result.rate!!, 1e-9)
    assertEquals(3, SkillEvalRun.calls(s))
  }

  @Test
  fun `the baseline answers without the skill and gives a delta`() {
    val s = suite("""{"baseline":true,"evals":[{"id":"a","prompt":"p","assertions":[{"type":"regex","pattern":"dry"}]}]}""")
    val script = Script(mutableListOf("dry-run", "просто выложу"))
    val result = SkillEvalRun.run("d", "m", "# SKILL BODY", s, script).results.single()
    assertTrue(script.seen[0][0].text.contains("# SKILL BODY"))
    assertFalse(script.seen[1][0].text.contains("# SKILL BODY"), "the baseline must not see the skill")
    assertEquals(1.0, result.delta!!, 1e-9)
    assertEquals(2, SkillEvalRun.calls(s))
  }

  @Test
  fun `runs are clamped and a bad assertion is refused at reading`() {
    assertEquals(SkillEvals.MAX_RUNS, suite("""{"runs":99,"evals":[{"prompt":"p"}]}""").runs)
    assertEquals(1, suite("""{"runs":"много","evals":[{"prompt":"p"}]}""").runs)
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read("""{"evals":[{"prompt":"p","assertions":[{"type":"regex","pattern":"[unclosed"}]}]}"""))
    assertIs<SkillEvals.Read.Broken>(SkillEvals.read("""{"evals":[{"prompt":"p","assertions":[{"type":"llm","pattern":"x"}]}]}"""))
  }
}
