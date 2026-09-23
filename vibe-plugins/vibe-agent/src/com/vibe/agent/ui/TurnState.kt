// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.acp.ToolCallRegistry
import com.vibe.agent.guard.Trifecta
import com.vibe.agent.pipelines.PipelineStep
import com.vibe.agent.pipelines.RolePaths
import com.vibe.agent.pipelines.StepLimits
import com.vibe.agent.pipelines.StepReport
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelPricing
import com.vibe.agent.providers.TokenUsage
import com.vibe.agent.providers.ToolRound
import java.awt.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * Everything one running turn owns: the chat's turn, or one step of a pipeline.
 *
 * A pipeline step used to borrow the panel's fields — its role, its write boundary, its answer buffer, its ceilings,
 * its changed files — and that is why two steps could never run at once: one step's write would have been checked
 * against the other's boundary, and their answers would have streamed into one buffer. Each turn now keeps its own,
 * and the panel routes every event by the session it came from ([TurnRouter]). The chat's ordinary turn is the same
 * object, so there is one way to run a turn.
 */
internal class TurnState(
  /** Role of a pipeline step; null in the chat. */
  val role: String?,
  /** Where the turn may write; empty — no boundary, as in the chat. */
  val scope: RolePaths.Scope = RolePaths.Scope(),
  /** The step whose ceilings apply, or null — an ordinary turn has none. */
  val limits: PipelineStep? = null,
  /** How the turn is named in its block and in a permission dialog; null in the chat. */
  val label: String? = null,
  /** Trifecta signals: the thread's for the chat (they add up over its turns), the step's own for a step. */
  val signals: MutableSet<Trifecta.Signal> = ConcurrentHashMap.newKeySet(),
  /** Where the turn's rows go: the feed itself, or the block of a step running in a wave. */
  @Volatile var feed: TurnFeed,
) {
  /** A step of a wave that has finished: its answer is a record now, and a redrawn feed needs no live block for it. */
  @Volatile var done = false

  /** The ACP session the turn runs in when it is not the chat's own; [TurnRouter] routes updates back by it. */
  @Volatile var sessionId: String? = null

  /** A step's own answer, for its report and for the next step; the chat does not keep one. */
  val answer: StringBuffer? = if (role != null) StringBuffer() else null

  val changedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
  @Volatile var hadMutatingTool = false

  val toolCalls = ToolCallRegistry()
  val toolStarts = ConcurrentHashMap<String, Long>()
  val toolCallCount = AtomicInteger(0)

  /** Shapes of the turn's own tool calls: two steps interleaving their calls would look like a cycle to one detector. */
  val loopHistory = com.vibe.agent.safety.LoopDetector.History()

  /** Window fill at the step's first usage report: a ceiling counts what the step added, not the session's total. */
  @Volatile var tokensBase: Long = -1L
  @Volatile var limitHit: StepLimits.Verdict? = null
  @Volatile var report: StepReport? = null

  /** The streamed text of the turn; the UI thread projects it into [message]. */
  val text = StringBuffer()

  /** A direct model's reasoning and tool rounds, stored with its answer. */
  val reasoning = StringBuffer()
  val toolRounds: MutableList<ToolRound> = java.util.Collections.synchronizedList(ArrayList())

  // The feed projection; the UI thread only.
  var uiConsumed = 0
  var message: AgentPanel.AgentMessage? = null
  var thoughts: ThoughtsBlock? = null

  /** A step on its own model has its own client: two steps sharing one would share its last usage and its cancel. */
  @Volatile var llm: LlmClient? = null
  @Volatile var usage: TokenUsage = TokenUsage.NONE
  @Volatile var pricing: ModelPricing? = null
}

/** Where the rows of a turn go. The UI thread only. */
internal interface TurnFeed {
  /** A row that stands for a record of the thread: an answer, a tool card. */
  fun addRecord(row: JComponent)

  /** A row that is not a record — reasoning, a live console — before [before] when it is there. */
  fun addExtra(row: JComponent, before: Component? = null)
}

/**
 * Which turn an event belongs to, by the session it came from.
 *
 * The chat's turn answers for its own session and for any session nobody registered, as every event did before steps
 * could run at once. A step is registered for the whole run, not only while it works: an update the agent sends
 * after the step's answer still belongs to that step.
 */
internal class TurnRouter(initial: TurnState) {
  @Volatile var chat: TurnState = initial
  private val steps = ConcurrentHashMap<String, TurnState>()

  fun of(sessionId: String?): TurnState = sessionId?.let { steps[it] } ?: chat

  fun register(turn: TurnState) {
    steps[checkNotNull(turn.sessionId) { "a step is routed by its session" }] = turn
  }

  fun unregisterAll() = steps.clear()

  /** The chat's turn and every registered step: where a tool call or a terminal of any of them can be found. */
  fun all(): List<TurnState> = listOf(chat) + steps.values
}
