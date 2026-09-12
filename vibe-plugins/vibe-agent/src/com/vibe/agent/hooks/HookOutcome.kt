// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Pure exit-code semantics for hooks, VibeIDE contract verbatim:
 * 0 = allowed (non-empty stdout becomes a note to the agent); 2 = refuse;
 * ANY other code, a timeout or a spawn failure = the hook itself is broken and
 * MUST NOT block the agent. Rationale: exit 1 is what any broken script returns
 * (missing binary, typo) — if 1 blocked, a typo in a hook would silently lock
 * the project. Stopping the agent must be deliberate (code 2).
 *
 * One exception to «0 = allowed»: a hook ported from Claude Code refuses with JSON on stdout and
 * keeps the code at 0 (see [foreignDecisionOf]). Read by the exit code alone, its refusal reached
 * the agent as advice and the action went through — so the deliberate «no» is honoured whichever of
 * the two contracts it is written in.
 */
enum class HookVerdict { OK, NOTE, REFUSE, BROKEN }

/** What a hook written for Claude Code said on exit 0. */
enum class ForeignDecision { REFUSE, ALLOW, ASK }

data class ForeignVerdict(val decision: ForeignDecision, val reason: String?)

data class HookResult(val hook: Hook, val verdict: HookVerdict, val message: String?)

/**
 * Aggregated decision for one event's hook chain. `blocked` is true only for
 * preToolUse; `flagged` records that a hook actually refused (exit 2), even when
 * that refusal cannot block (post/turnEnd) — the audit `ok` should reflect the
 * refusal, not just whether the action was stopped.
 */
data class HookDecision(val blocked: Boolean, val flagged: Boolean, val agentMessage: String?, val brokenHooks: List<String>)

object HookOutcome {
  const val REFUSE_EXIT_CODE = 2
  const val OUTPUT_LIMIT = 4000

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun verdictOf(hook: Hook, exitCode: Int?, timedOut: Boolean, spawnFailed: Boolean, stdout: String, stderr: String): HookResult {
    if (spawnFailed) return HookResult(hook, HookVerdict.BROKEN, t("hooks.result.spawnFailed", "hook" to hook.name()))
    if (timedOut) return HookResult(hook, HookVerdict.BROKEN, t("hooks.result.timedOut", "hook" to hook.name(), "timeout" to hook.timeoutMs))
    return when (exitCode) {
      0 -> when (val foreign = foreignDecisionOf(stdout)) {
        null -> {
          val note = clip(stdout)
          if (note.isNullOrBlank()) HookResult(hook, HookVerdict.OK, null)
          else HookResult(hook, HookVerdict.NOTE, note)
        }
        else -> when (foreign.decision) {
          ForeignDecision.REFUSE -> HookResult(hook, HookVerdict.REFUSE,
            foreign.reason?.let { clip(it) } ?: t("hooks.result.refusedSilently", "hook" to hook.name()))
          // An answer to the machine, not a message for the agent: showing `{"decision":"allow"}`
          // as a note would put JSON in front of the model on every allowed call.
          ForeignDecision.ALLOW -> HookResult(hook, HookVerdict.OK, null)
          // We have no third answer, and quietly reading «ask» as «allowed» would lose the intent.
          ForeignDecision.ASK -> HookResult(hook, HookVerdict.BROKEN, t("hooks.result.foreignAsk", "hook" to hook.name()))
        }
      }
      REFUSE_EXIT_CODE -> {
        val reason = clip(stderr).takeUnless { it.isNullOrBlank() }
          ?: clip(stdout).takeUnless { it.isNullOrBlank() }
          ?: t("hooks.result.refusedSilently", "hook" to hook.name())
        HookResult(hook, HookVerdict.REFUSE, reason)
      }
      else -> {
        val detail = listOf(clip(stderr), clip(stdout)).firstOrNull { !it.isNullOrBlank() }
        HookResult(hook, HookVerdict.BROKEN, t("hooks.result.badExit", "hook" to hook.name(), "code" to exitCode, "refuseCode" to REFUSE_EXIT_CODE) +
          (detail?.let { ": $it" } ?: ""))
      }
    }
  }

  /**
   * The decision of a hook written for Claude Code, or null when stdout is an ordinary note.
   *
   * The JSON test is theirs verbatim — stdout is a decision only when it starts with `{` and ends
   * with `}` — so a hook behaves the same in both hosts, and a note that merely mentions JSON stays
   * a note. Only documented shapes count: `hookSpecificOutput.permissionDecision`, the older
   * top-level `decision`, and `continue: false`, which is their way to say «stop here».
   */
  fun foreignDecisionOf(stdout: String): ForeignVerdict? {
    val text = stdout.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return null
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    (root["hookSpecificOutput"] as? JsonObject)?.let { specific ->
      specific.string("permissionDecision")?.let { permission ->
        return verdictFor(permission, specific.string("permissionDecisionReason"))
      }
    }
    root.string("decision")?.let { decision -> return verdictFor(decision, root.string("reason")) }
    if ((root["continue"] as? JsonPrimitive)?.booleanOrNull == false) {
      return ForeignVerdict(ForeignDecision.REFUSE, root.string("stopReason"))
    }
    return null
  }

  private fun verdictFor(decision: String, reason: String?): ForeignVerdict? = when (decision.lowercase()) {
    "deny", "block" -> ForeignVerdict(ForeignDecision.REFUSE, reason)
    "allow", "approve" -> ForeignVerdict(ForeignDecision.ALLOW, reason)
    "ask" -> ForeignVerdict(ForeignDecision.ASK, reason)
    else -> null
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeUnless { it.isEmpty() }

  /**
   * Fold a chain's results into one decision. A refusal wins over notes; notes
   * accumulate. Only preToolUse actually blocks — for post/turnEnd the action
   * already happened, so its message is a request to fix, not a block.
   */
  fun decideHooks(event: HookEvent, results: List<HookResult>): HookDecision {
    val broken = results.filter { it.verdict == HookVerdict.BROKEN }.map { it.hook.name() }
    val refuse = results.firstOrNull { it.verdict == HookVerdict.REFUSE }
    val notes = results.filter { it.verdict == HookVerdict.NOTE }.mapNotNull { it.message }
    val header = when (event) {
      HookEvent.PRE_TOOL_USE -> t("hooks.header.blocked")
      // Отказ гейта — не блокировка: он говорит «черновик не принят», а что с этим делать,
      // решает пайплайн (эскалация), а не механизм хуков.
      HookEvent.PIPELINE_STEP_END -> t("hooks.header.notAccepted")
      else -> t("hooks.header.flagged")
    }
    return when {
      refuse != null -> HookDecision(
        blocked = event == HookEvent.PRE_TOOL_USE,
        flagged = true,
        agentMessage = "$header ${refuse.message}",
        brokenHooks = broken,
      )
      notes.isNotEmpty() -> HookDecision(false, flagged = false, agentMessage = notes.joinToString("\n"), brokenHooks = broken)
      else -> HookDecision(false, flagged = false, agentMessage = null, brokenHooks = broken)
    }
  }

  private fun clip(s: String): String? {
    val t = s.trim()
    if (t.isEmpty()) return null
    return if (t.length > OUTPUT_LIMIT) t.take(OUTPUT_LIMIT) + "\n" + t("hooks.output.clipped") else t
  }
}
