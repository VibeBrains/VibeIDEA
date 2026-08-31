// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
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

    /** Something went wrong; the person decides what happens next. */
    STOP_UNSAFE,
  }

  data class State(
    val enabled: Boolean,
    /** Turns taken automatically since the person last spoke. */
    val autoTurnsDone: Int,
    val maxTurns: Int,
    /** Ask after this many automatic turns; 0 means never ask. */
    val checkpointEvery: Int,
    val plan: AgentPlan.Plan?,
    val lastTurnFailed: Boolean = false,
    val breakerTripped: Boolean = false,
    val stoppedByUser: Boolean = false,
  )

  fun decide(state: State): Decision {
    if (!state.enabled) return Decision.OFF
    // Order matters: an unsafe ending is checked before everything, including the plan being
    // finished, because a failed last step is not a finished plan even when the boxes are ticked.
    if (state.stoppedByUser || state.breakerTripped || state.lastTurnFailed) return Decision.STOP_UNSAFE
    val plan = state.plan
    if (plan == null || plan.isEmpty) return Decision.OFF
    if (plan.steps.all { it.status == AgentPlan.Status.COMPLETED }) return Decision.STOP_PLAN_DONE
    if (state.maxTurns > 0 && state.autoTurnsDone >= state.maxTurns) return Decision.STOP_LIMIT
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

  /** How many steps are still not done, for the line that says what remains. */
  fun remaining(plan: AgentPlan.Plan?): Int =
    plan?.steps?.count { it.status != AgentPlan.Status.COMPLETED } ?: 0
}
