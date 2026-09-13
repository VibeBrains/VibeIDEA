// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.providers.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One run of a skill's cases: ask the model, then grade, case by case.
 *
 * Orchestration only, with the model behind a lambda: a real run and a test differ in that lambda
 * and nothing else. Sequential on purpose — a run spends the owner's tokens, and a parallel fan-out
 * would spend them faster than the progress bar can be stopped.
 *
 * A failure is a result, not an exception: a provider that refused on the third case must not throw
 * away the two cases already judged.
 */
object SkillEvalRun {
  /** Talks to a model: messages in, answer out. Whatever the provider throws comes out as thrown. */
  fun interface Caller {
    fun ask(messages: List<ChatMessage>): String
  }

  /** One answer and its grade. */
  data class Attempt(val answer: String?, val verdict: SkillEvals.Verdict?, val error: String?) {
    val passed: Boolean get() = verdict?.passed == true
  }

  data class Result(
    val case: SkillEvals.Case,
    val attempts: List<Attempt>,
    /** The same case answered without the skill; empty when the suite asked for no baseline. */
    val baseline: List<Attempt> = emptyList(),
  ) {
    private val first: Attempt? get() = attempts.firstOrNull()
    val answer: String? get() = first?.answer
    val verdict: SkillEvals.Verdict? get() = first?.verdict
    val error: String? get() = first?.error

    /** Passed means EVERY attempt passed: a skill that works two times out of three does not work. */
    val passed: Boolean get() = attempts.isNotEmpty() && attempts.all { it.passed }
    val failed: Boolean get() = attempts.any { it.verdict?.passed == false }
    /** Nobody could say, and nothing failed. Counting these as passing would be a lie. */
    val unjudged: Boolean get() = !failed && attempts.any { it.verdict == null }

    val rate: Double? get() = rateOf(attempts)
    val baselineRate: Double? get() = rateOf(baseline)
    /** How much the skill moved the result; null without a baseline. */
    val delta: Double? get() { val a = rate ?: return null; val b = baselineRate ?: return null; return a - b }
  }

  data class Report(val skillId: String, val model: String, val finishedAtMs: Long, val results: List<Result>) {
    val passed: Int get() = results.count { it.passed }
    val failed: Int get() = results.count { it.failed }
    val unjudged: Int get() = results.count { it.unjudged }
  }

  /** Model calls a suite will make — named in the consent dialog before any money is spent. */
  fun calls(suite: SkillEvals.Suite): Int = suite.cases.sumOf { case ->
    val perAttempt = 1 + (if (SkillEvals.needsJudge(case)) 1 else 0)
    suite.runs * perAttempt * (if (suite.baseline) 2 else 1)
  }

  fun run(
    skillId: String,
    model: String,
    body: String,
    suite: SkillEvals.Suite,
    caller: Caller,
    attachments: (SkillEvals.Case) -> Map<String, String> = { emptyMap() },
    cancelled: () -> Boolean = { false },
    onProgress: (done: Int, case: SkillEvals.Case) -> Unit = { _, _ -> },
    now: () -> Long = System::currentTimeMillis,
  ): Report {
    val results = ArrayList<Result>()
    for ((index, case) in suite.cases.withIndex()) {
      if (cancelled()) break
      onProgress(index, case)
      val files = attachments(case)
      val attempts = (1..suite.runs).map { attempt(case, caller, SkillEvals.runMessages(body, case, files)) }
      val baseline = if (suite.baseline) (1..suite.runs).map { attempt(case, caller, SkillEvals.baselineMessages(case, files)) } else emptyList()
      results += Result(case, attempts, baseline)
    }
    return Report(skillId, model, now(), results)
  }

  private fun attempt(case: SkillEvals.Case, caller: Caller, messages: List<ChatMessage>): Attempt {
    val answer = runCatching { caller.ask(messages) }.getOrElse { return Attempt(null, null, reasonOf(it)) }
    val broken = SkillEvals.assertionFailures(case, answer)
    if (!SkillEvals.needsJudge(case)) return Attempt(answer, SkillEvals.Verdict(broken.isEmpty(), broken, null), null)
    val verdictText = runCatching { caller.ask(SkillEvals.judgeMessages(case, answer)) }
      .getOrElse { return Attempt(answer, null, reasonOf(it)) }
    val judged = SkillEvals.verdictOf(verdictText)
      ?: return Attempt(answer, null, verdictText.trim().take(REASON_LIMIT))
    // The free checks are as binding as the judge: either one failing fails the attempt.
    return Attempt(answer, SkillEvals.Verdict(judged.passed && broken.isEmpty(), judged.failures + broken, judged.note), null)
  }

  private fun rateOf(attempts: List<Attempt>): Double? =
    if (attempts.isEmpty()) null else attempts.count { it.passed }.toDouble() / attempts.size

  /** The report as JSON — written beside the runs, never into the skill directory (see [SkillEvals]). */
  fun toJson(report: Report): JsonObject = buildJsonObject {
    put("skill", report.skillId)
    put("model", report.model)
    put("finishedAt", report.finishedAtMs)
    put("passed", report.passed)
    put("failed", report.failed)
    put("unjudged", report.unjudged)
    put("cases", buildJsonArray {
      for (result in report.results) {
        add(buildJsonObject {
          put("id", result.case.id)
          put("prompt", result.case.prompt)
          result.verdict?.let { verdict ->
            put("passed", verdict.passed)
            put("failures", JsonArray(verdict.failures.map { JsonPrimitive(it) }))
            verdict.note?.let { put("note", it) }
          }
          result.error?.let { put("error", it) }
          result.answer?.let { put("answer", it.take(SkillEvals.ANSWER_LIMIT)) }
          if (result.attempts.size > 1) put("runs", result.attempts.size)
          result.rate?.let { if (result.attempts.size > 1) put("rate", it) }
          result.baselineRate?.let { put("baselineRate", it) }
          result.delta?.let { put("delta", it) }
        })
      }
    })
  }

  private const val REASON_LIMIT = 400

  private fun reasonOf(failure: Throwable): String =
    (failure.message ?: failure::class.simpleName ?: "error").take(REASON_LIMIT)
}
