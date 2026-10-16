// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

/**
 * When the direct chat's tool loop stops to ask the person, and when it goes on by itself
 *
 * The loop used to end the turn dead after eight rounds: an edit that needs a few reads and a grep never reached the edit
 * Now every [every] working rounds is a checkpoint, as VibeIDE's soft checkpoint is:
 * The person is asked whether to go on, and the same loop continues, so nothing is resent or redone
 * On the autopilot the checkpoint passes without a question while the stretch stays within [maxTokens]:
 * The same budget the plan autopilot spends, so the person's money has one ceiling, not two
 * Past the budget the autopilot asks too, because an unattended run must not grind on silently
 *
 * A round of tool searches only is not work: the model found its tools and did nothing with them yet
 * Counting it let a model that discovers tools one by one spend half the ceiling on discovery
 * [hardLimit] counts every round, searches included, and no answer lifts it: it is the runaway guard
 *
 * One instance per turn: it remembers where the next checkpoint is and what the person already agreed to spend
 */
class ToolLoopCheckpoint(
  private val every: Int,
  private val autopilot: Boolean,
  private val maxTokens: Long,
  private val hardLimit: Int = HARD_LIMIT,
) {
  enum class Verdict {
    /** Not a checkpoint: the next request goes out */
    GO_ON,

    /** A checkpoint the autopilot passes by itself: said in the feed, not asked */
    PASS_ON_AUTOPILOT,

    /** A checkpoint the person decides: go on in the same turn, or stop it */
    ASK,

    /** The hard limit: the turn ends whatever anyone says */
    STOP,
  }

  private var rounds = 0
  private var workRounds = 0
  private var nextAt = every
  private var agreedAt = 0L

  /** Working rounds so far: what the checkpoint counts and what the feed names */
  val work: Int get() = workRounds

  /** Every round so far, searches included */
  val total: Int get() = rounds

  /**
   * One round has run [calls]; [spentTokens] is what the stretch has spent so far
   * The verdict is about the request that would come next
   */
  fun afterRound(calls: List<ToolCall>, spentTokens: Long): Verdict {
    rounds++
    if (calls.any { it.name != com.vibe.agent.mcp.ToolSearch.NAME }) workRounds++
    if (rounds >= hardLimit) return Verdict.STOP
    if (every <= 0 || workRounds < nextAt) return Verdict.GO_ON
    nextAt = workRounds + every
    val spent = spentTokens - agreedAt
    return if (autopilot && (maxTokens <= 0 || spent < maxTokens)) Verdict.PASS_ON_AUTOPILOT else Verdict.ASK
  }

  /** The person said go on at [spentTokens]: the budget counts again from there, as it does after their message */
  fun personAgreed(spentTokens: Long) {
    agreedAt = spentTokens
  }

  /** A request that came back without a usable answer and is asked again counts towards the hard limit only */
  fun retried() {
    rounds++
  }

  companion object {
    /** No turn runs more rounds than this: VibeIDE's upper bound for its own loop */
    const val HARD_LIMIT = 200

    /**
     * Model-facing words that close a turn the client stopped, so plain English
     * Without them the model reads its own silence as a finished task, or starts it over when told to continue
     */
    fun stoppedMarker(rounds: Int): String =
      "[The client paused this turn after $rounds tool rounds. The task is not finished: " +
        "the tool results above are current, continue from the last one instead of reading the same files again.]"
  }
}
