// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.plans

/**
 * Two windows, one `plans.json`.
 *
 * Writing the whole file after reading it is the classic last-writer-wins: the second window saves
 * its own plan and, with it, an old picture of everybody else's — the first window's plan quietly
 * disappears. Nothing errors, nothing is logged, and the loss surfaces only when someone reopens a
 * thread and finds the checklist gone.
 *
 * The merge rule follows from who knows what:
 *
 *  • **for its own thread the writer is the authority** — it holds the live plan, and whatever is on
 *    disk for that thread is its own older write;
 *  • **for every other thread the newer timestamp wins** — the writer's copy of them is a snapshot
 *    from load time, and the file may have moved on since.
 *
 * Pure so the rule can be tested without two IDEs.
 */
object PlanMerge {
  /**
   * @param onDisk what the file holds right now, re-read immediately before writing
   * @param mine the writer's own picture, from its last load
   * @param ownThread the thread this save is about
   * @param ownPlan the plan to store, or null to remove the thread (finished or empty)
   */
  fun merge(
    onDisk: Map<String, AgentPlan.Plan>,
    mine: Map<String, AgentPlan.Plan>,
    ownThread: String,
    ownPlan: AgentPlan.Plan?,
  ): Map<String, AgentPlan.Plan> {
    val result = LinkedHashMap(onDisk)
    for ((threadId, plan) in mine) {
      if (threadId == ownThread) continue
      val existing = result[threadId]
      // Equal timestamps keep the disk version: the other writer got there first, and a tie is not
      // a reason to overwrite somebody else's work.
      if (existing == null || plan.updatedAtMs > existing.updatedAtMs) result[threadId] = plan
    }
    if (ownPlan == null) result.remove(ownThread) else result[ownThread] = ownPlan
    return result
  }

  /** Keeps the file bounded: the oldest plans go first, and the writer's own is never dropped. */
  fun trim(plans: Map<String, AgentPlan.Plan>, ownThread: String, limit: Int): Map<String, AgentPlan.Plan> {
    if (plans.size <= limit) return plans
    val own = plans[ownThread]
    val rest = plans.filterKeys { it != ownThread }
      .entries.sortedByDescending { it.value.updatedAtMs }
      .take(if (own == null) limit else limit - 1)
      .associate { it.toPair() }
    return if (own == null) rest else rest + (ownThread to own)
  }
}
