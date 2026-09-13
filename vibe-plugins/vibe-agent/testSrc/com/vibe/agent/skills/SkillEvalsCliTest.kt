// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkillEvalsCliTest {
  private fun report(vararg passed: Boolean?) = SkillEvalRun.Report("s", "m", 0, passed.mapIndexed { i, p ->
    SkillEvalRun.Result(SkillEvals.Case("c$i", "p"), listOf(SkillEvalRun.Attempt("a", p?.let { SkillEvals.Verdict(it, emptyList(), null) }, null)))
  })

  @Test
  fun `arguments parse with the command name first`() {
    val ok = assertIs<SkillEvalsCli.Parsed.Ok>(SkillEvalsCli.parse(listOf("vibe-evals", "/work/proj", "deploy", "--threshold", "0.8")))
    assertEquals(SkillEvalsCli.Options("/work/proj", "deploy", 0.8), ok.options)
  }

  @Test
  fun `the default threshold is every case`() {
    assertEquals(1.0, assertIs<SkillEvalsCli.Parsed.Ok>(SkillEvalsCli.parse(listOf("/p", "s"))).options.threshold)
  }

  @Test
  fun `bad arguments are errors, not guesses`() {
    assertIs<SkillEvalsCli.Parsed.Error>(SkillEvalsCli.parse(listOf("/p")))
    assertIs<SkillEvalsCli.Parsed.Error>(SkillEvalsCli.parse(listOf("/p", "s", "--threshold", "1.5")))
    assertIs<SkillEvalsCli.Parsed.Error>(SkillEvalsCli.parse(listOf("/p", "s", "--json")))
  }

  @Test
  fun `exit code follows the threshold and unjudged does not pass`() {
    assertEquals(SkillEvalsCli.EXIT_PASSED, SkillEvalsCli.exitCode(report(true, true), 1.0))
    assertEquals(SkillEvalsCli.EXIT_BELOW, SkillEvalsCli.exitCode(report(true, null), 1.0))
    assertEquals(SkillEvalsCli.EXIT_PASSED, SkillEvalsCli.exitCode(report(true, false), 0.5))
    assertEquals(SkillEvalsCli.EXIT_ERROR, SkillEvalsCli.exitCode(report(), 1.0))
  }
}
