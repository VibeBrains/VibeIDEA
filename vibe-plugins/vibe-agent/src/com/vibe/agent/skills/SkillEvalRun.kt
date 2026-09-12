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
 * One run of a skill's cases: ask the model, then ask the judge, case by case.
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

  data class Result(
    val case: SkillEvals.Case,
    val answer: String?,
    val verdict: SkillEvals.Verdict?,
    /** Why this case has no verdict: the provider refused, or the judge did not answer in form. */
    val error: String?,
  ) {
    val passed: Boolean get() = verdict?.passed == true
  }

  data class Report(val skillId: String, val model: String, val finishedAtMs: Long, val results: List<Result>) {
    val passed: Int get() = results.count { it.passed }
    val failed: Int get() = results.count { it.verdict?.passed == false }
    /** Neither passed nor failed: nobody could say. Counting these as passing would be a lie. */
    val unjudged: Int get() = results.count { it.verdict == null }
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
      val answered = runCatching { caller.ask(SkillEvals.runMessages(body, case, attachments(case))) }
      val answer = answered.getOrElse { failure ->
        results += Result(case, null, null, reasonOf(failure))
        continue
      }
      val judged = runCatching { caller.ask(SkillEvals.judgeMessages(case, answer)) }
      val verdictText = judged.getOrElse { failure ->
        results += Result(case, answer, null, reasonOf(failure))
        continue
      }
      val verdict = SkillEvals.verdictOf(verdictText)
      results += Result(case, answer, verdict, if (verdict == null) verdictText.trim().take(REASON_LIMIT) else null)
    }
    return Report(skillId, model, now(), results)
  }

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
        })
      }
    })
  }

  private const val REASON_LIMIT = 400

  private fun reasonOf(failure: Throwable): String =
    (failure.message ?: failure::class.simpleName ?: "error").take(REASON_LIMIT)
}
