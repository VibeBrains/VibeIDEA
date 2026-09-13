// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One append-only audit record, VibeIDE `.vibe/audit.jsonl` contract:
 * a single `JSON.stringify(event)` per line. `ts` (unix millis) is the only
 * timestamp; the rest is deliberately narrow. Privacy is enforced by the
 * callers (see [com.vibe.agent.audit.ToolCallAudit]): tool arguments, command
 * bodies, search queries and file contents are NEVER written — only a tool
 * name and, for file tools, a truncated target path.
 */
data class AuditEvent(
  val ts: Long,
  val action: String,
  val ok: Boolean,
  /**
   * Who caused this. Deliberately WITHOUT a default: a default would be chosen once here and then
   * silently inherited by every future call site, which is exactly how the journal lost the answer
   * to «по чьей воле» in the first place. Making it a compile error is the point.
   */
  val actor: AuditActor,
  /**
   * Вызов инструмента, породивший запись, — `toolCallId` из реестра вызовов.
   *
   * Без него системное действие и просьба модели связывались только по времени: «модель попросила
   * терминал» и «выполнилась команда, записался файл» лежали в журнале двумя независимыми строками.
   * Поле обязано стоять у всего, что порождено вызовом: `tool_call:*`, `fs_write`, `terminal`,
   * `permission`, `hook`.
   */
  val callId: String? = null,
  /** Ход, внутри которого всё это произошло: цепочка `prompt → tool_call → fs_write` читается целиком. */
  val turnId: String? = null,
  /**
   * The conversation thread: a session spans many turns.
   *
   * A leak can be legal call by call and turn by turn and only show as a sequence across the thread;
   * reading that sequence must be a filter, not a join over timestamps.
   */
  val sessionId: String? = null,
  val files: List<String>? = null,
  val model: String? = null,
  val latencyMs: Long? = null,
  val meta: Map<String, String>? = null,
) {
  fun toJson(): JsonObject = buildJsonObject {
    put("ts", ts)
    put("action", action)
    put("ok", ok)
    put("actor", actor.toJson())
    callId?.let { put("callId", it) }
    turnId?.let { put("turnId", it) }
    sessionId?.let { put("sessionId", it) }
    files?.takeIf { it.isNotEmpty() }?.let { list ->
      put("files", kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) }))
    }
    model?.let { put("model", it) }
    latencyMs?.let { put("latencyMs", it) }
    meta?.takeIf { it.isNotEmpty() }?.let { m ->
      put("meta", buildJsonObject { m.forEach { (k, v) -> put(k, v) } })
    }
  }

  /**
   * Closed set of audit actions (VibeIDE union, trimmed to what the ACP client
   * can actually observe). New actions must be added here so the format stays a
   * documented contract, not an open bag of strings.
   */
  object Action {
    const val PROMPT = "prompt"
    const val REPLY = "reply"
    const val TOOL_CALL_START = "tool_call:start"
    const val TOOL_CALL_DONE = "tool_call:done"
    const val PERMISSION = "permission"
    const val FS_WRITE = "fs_write"
    const val HOOK = "hook"
    const val VERIFY_GATE = "verify_gate:result"
    const val TURN_CHECK = "turn_check:result"
    const val CIRCUIT_BREAKER_OPENED = "circuit_breaker_opened"
    const val CIRCUIT_BREAKER_RECOVERED = "circuit_breaker_recovered"
    const val CHECKPOINT = "checkpoint"
    const val TERMINAL = "terminal"

    /**
     * Ответила не та модель, которую просили.
     *
     * Отдельным событием, а не полем у `reply`: между нами и вендором бывают прокси, агрегатор и
     * наша же цепочка запасных целей. Разбор задним числом «почему ответ другого качества и по
     * какой цене» начинается именно с этого вопроса. В `meta` — `asked` и `answered`.
     */
    const val MODEL_SUBSTITUTED = "model_substituted"

    /**
     * A person withdrew every approval of the project in one step — skills and commands.
     *
     * Granting was always one click, withdrawing was «edit the text and wait for the next question».
     * In `meta` — `skills` and `commands`, how many approvals were cleared.
     */
    const val REVOKE = "revoke"

    /**
     * Агент попросил у человека данные (`elicitation/create`) — и что тот ответил.
     *
     * Отдельно от `permission`, потому что это другой вопрос: разрешение отвечает «да/нет» на
     * действие, а здесь в ход уезжает ЗНАЧЕНИЕ, которое человек ввёл руками. При разборе «почему
     * агент сделал это» первым делом спрашивают, чем он руководствовался, — имена введённых полей
     * и есть ответ. Значения не пишем: в форму вводят и токены тоже.
     */
    const val ELICITATION = "elicitation"

    /**
     * Дежурная проверка нашла повод.
     *
     * Пишется ПРИЧИНА (какая проба и с каким кодом), а не факт: проверка, о которой нельзя
     * спросить «почему она сработала», через неделю выключается целиком.
     */
    const val PATROL = "patrol"

    /**
     * A secret was substituted into something that then ran.
     *
     * The NAME is recorded and never the value: when an incident is being unpicked, the first
     * question is «какие токены уехали вместе с этой командой», and today the log shows the command
     * and says nothing about what travelled with it. A log that answered by printing the token
     * would be the leak it was meant to help investigate.
     */
    const val SECRET_USED = "secret_used"

    /**
     * The IDE started an external agent's process: the command (masked), its folder, whether it
     * may run commands through us, and the NAMES of the secrets it was given. Together with
     * [AGENT_EXIT] it bounds everything the agent did with the person's authority.
     */
    const val AGENT_START = "agent:start"

    /** The agent's process ended on its own, with this exit code. */
    const val AGENT_EXIT = "agent:exit"

    /**
     * The IDE signed the agent in by the protocol (`authenticate`): which method, and whether the
     * agent accepted it. Never a credential — the sign-in is the agent's own flow. A terminal method
     * is not recorded here: it runs outside the IDE, and the reconnect after it is an [AGENT_START].
     */
    const val AGENT_AUTH = "agent:auth"

    /** The IDE asked the agent to log out (`logout`), and whether it did. */
    const val AGENT_LOGOUT = "agent:logout"
  }
}
