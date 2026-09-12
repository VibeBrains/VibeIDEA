// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Cases that check a skill, kept in `evals/evals.json` inside the skill directory.
 *
 * The format is the skill-creator schema from Anthropic, taken verbatim, so a set of cases written
 * for that tooling runs here and ours runs there. A skill is an instruction, and an instruction is
 * checked the way it is used: the model gets the skill body and the case prompt and answers in
 * words — no tools (decision №89). The skill under test may well be the one that deploys or deletes,
 * and a test suite must not be the thing that runs it.
 *
 * Nothing is ever written back into the skill directory: approval hashes that directory whole
 * (see [SkillApproval]), so a report beside the cases would ask the owner to approve the skill again
 * after every run.
 *
 * Everything here is pure: parsing, the two prompts and reading the verdict. The calls themselves
 * live in [SkillEvalRun].
 */
object SkillEvals {
  const val DIR = "evals"
  const val FILE = "evals.json"

  /** How much of the answer the judge is shown. A case that needs more than this is not a case. */
  const val ANSWER_LIMIT = 8000

  data class Case(
    val id: String,
    val prompt: String,
    val files: List<String> = emptyList(),
    val expectedOutput: String? = null,
    val expectations: List<String> = emptyList(),
  )

  data class Suite(val skillName: String?, val description: String?, val cases: List<Case>)

  /** What the judge said about one case. */
  data class Verdict(val passed: Boolean, val failures: List<String>, val note: String?)

  sealed interface Read {
    data class Ok(val suite: Suite) : Read

    /** No `evals/evals.json` at all — not a fault: most skills have no cases yet. */
    object Missing : Read

    data class Broken(val reason: String) : Read
  }

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun read(text: String?): Read {
    if (text == null) return Read.Missing
    if (text.isBlank()) return Read.Broken(t("skills.evals.badJson"))
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
      ?: return Read.Broken(t("skills.evals.badJson"))
    val raw = root["evals"] as? JsonArray ?: return Read.Broken(t("skills.evals.noCases"))
    val cases = ArrayList<Case>()
    raw.forEachIndexed { index, element ->
      val obj = element as? JsonObject ?: return Read.Broken(t("skills.evals.badCase", "index" to index + 1))
      val prompt = obj.string("prompt") ?: return Read.Broken(t("skills.evals.noPrompt", "index" to index + 1))
      cases += Case(
        id = obj.string("id") ?: (index + 1).toString(),
        prompt = prompt,
        files = obj.strings("files"),
        expectedOutput = obj.string("expected_output"),
        expectations = obj.strings("expectations"),
      )
    }
    if (cases.isEmpty()) return Read.Broken(t("skills.evals.noCases"))
    val ids = cases.map { it.id }
    if (ids.size != ids.distinct().size) return Read.Broken(t("skills.evals.duplicateId"))
    return Read.Ok(Suite(root.string("skill_name"), root.string("description"), cases))
  }

  /**
   * What the model under test is asked: the skill body as the system message — exactly how a skill
   * reaches a model in the chat — and the case prompt as the person's request, with the attached
   * files inlined so a case does not depend on the model's reading tools it is not given.
   */
  fun runMessages(skillBody: String, case: Case, attachments: Map<String, String>): List<ChatMessage> {
    val system = skillBody.trim() + "\n\n" + NO_TOOLS
    val user = buildString {
      append(case.prompt.trim())
      for ((path, content) in attachments) {
        append("\n\n<file path=\"").append(path).append("\">\n").append(content.trim()).append("\n</file>")
      }
    }
    return listOf(ChatMessage("system", system), ChatMessage("user", user))
  }

  /** What the judge is asked: the checklist of the case and the answer as it came. */
  fun judgeMessages(case: Case, answer: String): List<ChatMessage> {
    val user = buildString {
      append("<case id=\"").append(case.id).append("\">\n")
      append("<prompt>\n").append(case.prompt.trim()).append("\n</prompt>\n")
      case.expectedOutput?.let { append("<expected_output>\n").append(it.trim()).append("\n</expected_output>\n") }
      if (case.expectations.isNotEmpty()) {
        append("<expectations>\n")
        case.expectations.forEachIndexed { index, item -> append(index + 1).append(". ").append(item.trim()).append("\n") }
        append("</expectations>\n")
      }
      append("<answer>\n").append(answer.trim().take(ANSWER_LIMIT)).append("\n</answer>")
    }
    return listOf(ChatMessage("system", JUDGE_RULES), ChatMessage("user", user))
  }

  /** The judge's answer as a verdict, or null when it did not answer in the asked form. */
  fun verdictOf(answer: String): Verdict? {
    val text = answer.trim().removeSurrounding("```").trim()
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val body = text.substring(start, end + 1)
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val passed = (obj["passed"] as? JsonPrimitive)?.booleanOrNull ?: return null
    return Verdict(passed, obj.strings("failures"), obj.string("note"))
  }

  /**
   * Said to the model under test. In English on purpose: it is not interface text, and translating
   * it with the interface language would quietly change how every case is answered.
   */
  private const val NO_TOOLS =
    "You are being evaluated. You have no tools in this run: do not call anything and do not report " +
    "a command as already executed. Answer as you would answer the person — say what you would do, " +
    "in what order, and what you would ask or show before doing it."

  private const val JUDGE_RULES =
    "You grade one answer against a checklist. Reply with JSON only — no prose, no code fences:\n" +
    "{\"passed\": true|false, \"failures\": [\"the expectation that is not met, quoted\"], " +
    "\"note\": \"one short sentence\"}\n" +
    "An expectation counts as met only when the answer clearly shows it. Silence is not compliance: " +
    "if the answer simply does not mention what an expectation asks for, that expectation is not met. " +
    "Grade the answer as written, never what its author probably meant. `passed` is true only when " +
    "`failures` is empty."

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeUnless { it.isEmpty() }

  private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeUnless { s -> s.isEmpty() } }

  private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
