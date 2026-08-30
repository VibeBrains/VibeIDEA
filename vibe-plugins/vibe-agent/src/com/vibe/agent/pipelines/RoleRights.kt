// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

/**
 * What a role may actually DO — enforced, not requested in the prompt.
 *
 * A reviewer told «не меняй файлы, только отчёт» obeys most of the time. Most of the time is the
 * problem: the one run where it "just fixes" what it found is the run where the review and the fix
 * are the same act, and nobody reviewed the fix. The same for security. So the restriction lives
 * where it cannot be talked out of: the write is refused.
 *
 * The list is deliberately short and boring. Every role that produces code writes; every role whose
 * output is a JUDGEMENT does not. `explore` is read-only for the same reason a reconnaissance
 * party does not rebuild the bridge it was sent to look at.
 */
object RoleRights {
  data class Rights(
    val canWrite: Boolean,
    /** Running commands is not writing files, but it is how a read-only role writes anyway. */
    val canRunCommands: Boolean,
  )

  private val READ_ONLY = Rights(canWrite = false, canRunCommands = false)
  private val FULL = Rights(canWrite = true, canRunCommands = true)

  /** A role that reads and reports; running the build to see it fail is part of reporting. */
  private val READ_AND_RUN = Rights(canWrite = false, canRunCommands = true)

  private val BY_ROLE: Map<String, Rights> = mapOf(
    "explore" to READ_ONLY,
    "code-reviewer" to READ_AND_RUN,
    "security" to READ_AND_RUN,
    "qa" to READ_AND_RUN,
    "planner" to READ_ONLY,
    "designer" to FULL,
    "frontend-dev" to FULL,
    "backend-dev" to FULL,
    "implement-step" to FULL,
    "recover-or-skip" to FULL,
    "orchestrator" to FULL,
  )

  /**
   * An unknown role gets FULL rights on purpose: this table describes pipeline roles, and an
   * ordinary chat — where there is no role at all — must not become read-only because a lookup
   * missed. Restrictions that appear from nowhere are worse than none.
   */
  fun of(role: String?): Rights = role?.let { BY_ROLE[it.trim().lowercase()] } ?: FULL

  fun mayWrite(role: String?): Boolean = of(role).canWrite

  fun mayRunCommands(role: String?): Boolean = of(role).canRunCommands

  /** Roles whose whole output is a judgement — listed once so the doc and the code cannot drift. */
  fun readOnlyRoles(): List<String> = BY_ROLE.filterValues { !it.canWrite }.keys.sorted()
}
