// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

/**
 * Pure exit-code semantics for hooks, VibeIDE contract verbatim:
 * 0 = allowed (non-empty stdout becomes a note to the agent); 2 = refuse;
 * ANY other code, a timeout or a spawn failure = the hook itself is broken and
 * MUST NOT block the agent. Rationale: exit 1 is what any broken script returns
 * (missing binary, typo) — if 1 blocked, a typo in a hook would silently lock
 * the project. Stopping the agent must be deliberate (code 2).
 */
enum class HookVerdict { OK, NOTE, REFUSE, BROKEN }

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

  fun verdictOf(hook: Hook, exitCode: Int?, timedOut: Boolean, spawnFailed: Boolean, stdout: String, stderr: String): HookResult {
    if (spawnFailed) return HookResult(hook, HookVerdict.BROKEN, "хук «${hook.name()}» не запустился")
    if (timedOut) return HookResult(hook, HookVerdict.BROKEN, "хук «${hook.name()}» не уложился в ${hook.timeoutMs} мс и был остановлен")
    return when (exitCode) {
      0 -> {
        val note = clip(stdout)
        if (note.isNullOrBlank()) HookResult(hook, HookVerdict.OK, null)
        else HookResult(hook, HookVerdict.NOTE, note)
      }
      REFUSE_EXIT_CODE -> {
        val reason = clip(stderr).takeUnless { it.isNullOrBlank() }
          ?: clip(stdout).takeUnless { it.isNullOrBlank() }
          ?: "хук «${hook.name()}» отклонил действие без объяснения"
        HookResult(hook, HookVerdict.REFUSE, reason)
      }
      else -> {
        val detail = listOf(clip(stderr), clip(stdout)).firstOrNull { !it.isNullOrBlank() }
        HookResult(hook, HookVerdict.BROKEN, "хук «${hook.name()}» завершился с кодом $exitCode (отказ — это код $REFUSE_EXIT_CODE)" +
          (detail?.let { ": $it" } ?: ""))
      }
    }
  }

  /**
   * Fold a chain's results into one decision. A refusal wins over notes; notes
   * accumulate. Only preToolUse actually blocks — for post/turnEnd the action
   * already happened, so its message is a request to fix, not a block.
   */
  fun decideHooks(event: HookEvent, results: List<HookResult>): HookDecision {
    val broken = results.filter { it.verdict == HookVerdict.BROKEN }.map { it.hook.name() }
    val refuse = results.firstOrNull { it.verdict == HookVerdict.REFUSE }
    val notes = results.filter { it.verdict == HookVerdict.NOTE }.mapNotNull { it.message }
    val header = if (event == HookEvent.PRE_TOOL_USE) "Действие остановлено проверкой проекта:"
                 else "Проверка проекта нашла проблему в том, что только что сделано:"
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
    return if (t.length > OUTPUT_LIMIT) t.take(OUTPUT_LIMIT) + "\n…(вывод обрезан)" else t
  }
}
