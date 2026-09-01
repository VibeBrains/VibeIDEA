// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * By whose will the record happened: the person, the agent, or the IDE itself.
 *
 * Until this existed the journal answered «что произошло» and never «по чьей воле»: a file written
 * by the person and the same file written by the agent during an unattended autopilot stretch were
 * one and the same line. NIST names that an accountability gap and calls separate attribution a
 * condition of non-repudiation, and an incident is unpicked in exactly this order — first who,
 * then what.
 *
 * Three kinds rather than two, because we would otherwise have to lie: a circuit breaker that
 * opened and a verify gate that failed are neither the person nor the agent, they are our own
 * checks, and calling them «agent» would put the IDE's decisions on the agent's account.
 */
data class AuditActor(
  val kind: Kind,
  /** The pipeline role the agent was playing, when it was playing one. */
  val role: String? = null,
  /** Which agent — the ACP config name or the model target; null for the person and the IDE. */
  val agent: String? = null,
) {
  enum class Kind(val wire: String) {
    HUMAN("human"),
    AGENT("agent"),
    IDE("ide"),
  }

  fun toJson(): JsonObject = buildJsonObject {
    put("kind", kind.wire)
    role?.takeIf { it.isNotBlank() }?.let { put("role", it) }
    agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
  }

  companion object {
    /** The person did it themselves: typed, clicked, approved, refused. */
    val HUMAN = AuditActor(Kind.HUMAN)

    /** Our own machinery: gates, hooks, breakers, detectors. */
    val IDE = AuditActor(Kind.IDE)

    /** The agent did it — with the role it was playing and the agent that was running. */
    fun agent(role: String? = null, agent: String? = null): AuditActor =
      AuditActor(Kind.AGENT, role?.takeIf { it.isNotBlank() }, agent?.takeIf { it.isNotBlank() })
  }
}
