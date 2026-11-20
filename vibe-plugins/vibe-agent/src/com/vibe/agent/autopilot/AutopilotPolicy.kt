// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.autopilot

import com.vibe.agent.plans.AgentPlan

/**
 * Whether the agent may take the next step by itself, and when it must stop and ask.
 *
 * A long task is a chain of short turns, and the human contribution to most of them is the word
 * «продолжай». Automating that word is the whole feature — and the reason it needs a policy rather
 * than a loop is that an agent which continues unconditionally is not an autopilot but a runaway:
 * it resumes a failed turn, it resumes into a tripped breaker, and it does so until the money runs
 * out.
 *
 * Two hard rules shape everything here:
 *
 *  1. **Autopilot needs a plan.** «Продолжай» means something only when there is a written next
 *     step. Without a plan the word is a guess about what the person wanted, and a guess repeated
 *     automatically is the expensive kind.
 *  2. **Anything unusual hands control back.** A failure, a stop, a tripped breaker — the autopilot
 *     never decides that it understands the situation better than the person who is about to read
 *     it.
 *  3. **A question ends the autopilot.** «Shall I start?» is the agent asking the person, not a
 *     step; continuing would answer it on their behalf.
 */
object AutopilotPolicy {
  enum class Decision {
    /** Autopilot is off, or there is no plan to walk. */
    OFF,

    /** Take the next step without asking. */
    CONTINUE,

    /** Ask the person before going further: the checkpoint has come round. */
    CHECKPOINT,

    /** The plan is finished — the good ending. */
    STOP_PLAN_DONE,

    /** The turn budget for one unattended stretch is spent. */
    STOP_LIMIT,

    /**
     * The token budget for one unattended stretch is spent.
     *
     * Separate from STOP_LIMIT because turns are not money: one turn on a large model costs more
     * than ten on a small one, and cost is what actually limits people — 79.8 % of the Temporal
     * 2026 respondents named token and compute cost a limiting factor.
     */
    STOP_BUDGET,

    /** Something went wrong; the person decides what happens next. */
    STOP_UNSAFE,

    /**
     * The turn ended with a question to the person.
     *
     * A model that has just written a plan asks «shall I start?» — and an autopilot that answers
     * for the person has approved the plan nobody read. A question is the agent handing the turn
     * back, so the autopilot stops even in a mode that would otherwise continue (owner, 20.11.2026).
     */
    STOP_QUESTION,
  }

  data class State(
    val enabled: Boolean,
    /** Turns taken automatically since the person last spoke. */
    val autoTurnsDone: Int,
    val maxTurns: Int,
    /** Ask after this many automatic turns; 0 means never ask. */
    val checkpointEvery: Int,
    val plan: AgentPlan.Plan?,
    /** Tokens spent since the person last spoke; 0 with [maxTokens] 0 means the ceiling is off. */
    val spentTokens: Long = 0,
    val maxTokens: Long = 0,
    val lastTurnFailed: Boolean = false,
    val breakerTripped: Boolean = false,
    val stoppedByUser: Boolean = false,
    /** The last words of the turn were a question addressed to the person ([endsWithQuestion]). */
    val endedWithQuestion: Boolean = false,
  )

  fun decide(state: State): Decision {
    if (!state.enabled) return Decision.OFF
    // Order matters: an unsafe ending is checked before everything, including the plan being
    // finished, because a failed last step is not a finished plan even when the boxes are ticked.
    if (state.stoppedByUser || state.breakerTripped || state.lastTurnFailed) return Decision.STOP_UNSAFE
    val plan = state.plan
    if (plan == null || plan.isEmpty) return Decision.OFF
    // A question is the agent asking the person, not a step of the plan: answering it with
    // «continue» would approve on the person's behalf exactly what they were asked about.
    if (state.endedWithQuestion) return Decision.STOP_QUESTION
    if (plan.steps.all { it.status == AgentPlan.Status.COMPLETED }) return Decision.STOP_PLAN_DONE
    if (state.maxTurns > 0 && state.autoTurnsDone >= state.maxTurns) return Decision.STOP_LIMIT
    if (state.maxTokens > 0 && state.spentTokens >= state.maxTokens) return Decision.STOP_BUDGET
    if (state.checkpointEvery > 0 && state.autoTurnsDone > 0 && state.autoTurnsDone % state.checkpointEvery == 0) {
      return Decision.CHECKPOINT
    }
    return Decision.CONTINUE
  }

  /** The step the checkpoint question is about — the one the person is being asked to bless. */
  fun currentStep(plan: AgentPlan.Plan?): String? {
    val steps = plan?.steps ?: return null
    return (steps.firstOrNull { it.status == AgentPlan.Status.IN_PROGRESS }
      ?: steps.firstOrNull { it.status == AgentPlan.Status.PENDING})?.content
  }

  /**
   * Did the turn end with a question to the person?
   *
   * The last non-empty line is what the person reads last, so it is the one that decides: a
   * trailing quote, bracket or markdown emphasis after the question mark is not a different ending.
   * Deliberately simple — a question mark anywhere else in the text says nothing about who the turn
   * was left to.
   */
  fun endsWithQuestion(text: String?): Boolean {
    val last = text?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() } ?: return false
    val end = last.trimEnd { it.isWhitespace() || it in "\"'»”’)]}*_" }
    return end.endsWith("?") || end.endsWith("？")
  }

  /** How many steps are still not done, for the line that says what remains. */
  fun remaining(plan: AgentPlan.Plan?): Int =
    plan?.steps?.count { it.status != AgentPlan.Status.COMPLETED } ?: 0
}
