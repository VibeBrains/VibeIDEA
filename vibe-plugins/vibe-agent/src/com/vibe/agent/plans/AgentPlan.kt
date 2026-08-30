// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.plans

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The agent's plan, kept as data rather than as a line that scrolls away.
 *
 * A long task is a plan the agent narrates once and then forgets to mention again. When the IDE is
 * restarted mid-task — and it is, because that is when one installs the update — everything about
 * that plan is gone: which steps were done, which was in progress, what the whole thing was for.
 * The user is left with a chat that ends mid-sentence and no way to ask «продолжай» meaningfully.
 *
 * So the plan is parsed from the ACP `plan` update, kept per thread, and survives a restart.
 */
object AgentPlan {
  enum class Status { PENDING, IN_PROGRESS, COMPLETED }

  data class Step(val content: String, val status: Status, val priority: String? = null)

  data class Plan(val steps: List<Step>, val updatedAtMs: Long = 0) {
    val done: Int get() = steps.count { it.status == Status.COMPLETED }
    val total: Int get() = steps.size
    val isFinished: Boolean get() = steps.isNotEmpty() && steps.all { it.status == Status.COMPLETED }
    val isEmpty: Boolean get() = steps.isEmpty()
    val current: Step? get() = steps.firstOrNull { it.status == Status.IN_PROGRESS }
                                ?: steps.firstOrNull { it.status == Status.PENDING }
  }

  /** ACP sends the WHOLE plan each time, so an update replaces rather than merges. */
  fun parse(update: JsonObject, nowMs: Long = 0): Plan {
    val entries = (update["entries"] as? JsonArray)
      ?: (update["plan"] as? JsonObject)?.get("entries") as? JsonArray
      ?: return Plan(emptyList(), nowMs)
    val steps = entries.mapNotNull { element ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      val content = entry["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      if (content.isEmpty()) return@mapNotNull null
      Step(content, statusOf(entry["status"]?.jsonPrimitive?.contentOrNull), entry["priority"]?.jsonPrimitive?.contentOrNull)
    }
    return Plan(steps, nowMs)
  }

  fun statusOf(wire: String?): Status = when (wire?.lowercase()) {
    "completed", "done" -> Status.COMPLETED
    "in_progress", "inprogress", "running" -> Status.IN_PROGRESS
    // An unknown status is PENDING on purpose: calling an unknown step «done» is the one error
    // that loses work, because a finished plan is never resumed.
    else -> Status.PENDING
  }

  fun encode(plan: Plan): JsonObject = buildJsonObject {
    put("updatedAt", plan.updatedAtMs)
    put("steps", JsonArray(plan.steps.map { step ->
      buildJsonObject {
        put("content", step.content)
        put("status", step.status.name.lowercase())
        step.priority?.let { put("priority", it) }
      }
    }))
  }

  fun decode(json: JsonObject): Plan {
    val steps = (json["steps"] as? JsonArray).orEmpty().mapNotNull { element ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      val content = entry["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      Step(content, statusOf(entry["status"]?.jsonPrimitive?.contentOrNull), entry["priority"]?.jsonPrimitive?.contentOrNull)
    }
    return Plan(steps, json["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0)
  }

  /** Checklist for the feed; [marks] keeps the glyphs out of this object and in the theme. */
  fun render(plan: Plan, marks: (Status) -> String): String =
    plan.steps.joinToString("\n") { "${marks(it.status)} ${it.content}" }
}
