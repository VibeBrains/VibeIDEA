// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.diagnostic.logger
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ProviderHttpException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * The request to an agent ended because the connection to it is gone, as opposed to the agent answering with an error
 * The pending requests of a client are failed with this when the client is stopped, the process exits or the pipe breaks
 * The message stays the diagnostic text it always was; what the person reads comes from [AgentFailure.reasonOf]
 */
class AgentGoneException(val kind: Kind, message: String, val detail: String = "") : IllegalStateException(message) {
  enum class Kind {
    /** The chat stopped the client: the Stop button, a change of agent, a reconnect */
    STOPPED,

    /** The process of the agent ended; [detail] is its exit code */
    EXITED,

    /** The pipe to the agent broke; [detail] is what the system said */
    PIPE_CLOSED,
  }
}

/**
 * The words for a failed request of an agent, as the person reads them in the feed
 *
 * A request that is waited for with `get()` fails with an `ExecutionException` whose message is `cause.toString()`,
 * which is where `java.lang.IllegalStateException: agent stopped` in the feed came from: a class name and a sentence in English
 * Here the wrappers are taken off, a lost connection or a program that could not be started is told in words of its own
 * with what to do, and anything else keeps its message
 */
object AgentFailure {
  /** How many wrappers are taken off looking for the cause: futures wrap a failure once or twice */
  private const val CAUSE_DEPTH = 8

  /** The exceptions that only carry another one */
  private fun isWrapper(error: Throwable): Boolean =
    error is ExecutionException || error is CompletionException || error is InvocationTargetException

  /** The failure itself, below the wrappers */
  fun rootOf(error: Throwable): Throwable {
    var current = error
    repeat(CAUSE_DEPTH) {
      val cause = current.cause
      if (!isWrapper(current) || cause == null) return current
      current = cause
    }
    return current
  }

  fun reasonOf(error: Throwable): String {
    val root = rootOf(error)
    if (root is AgentGoneException) {
      return when (root.kind) {
        AgentGoneException.Kind.STOPPED -> t("chat.agentGone.stopped")
        AgentGoneException.Kind.EXITED -> t("chat.agentGone.exited", "code" to root.detail)
        AgentGoneException.Kind.PIPE_CLOSED -> t("chat.agentGone.pipeClosed", "reason" to root.detail)
      }
    }
    if (root is AgentLaunchException) {
      return when (root.kind) {
        AgentLaunchException.Kind.NOT_FOUND ->
          if (root.nodeTool) t("chat.agentLaunch.nodeMissing", "command" to root.command)
          else t("chat.agentLaunch.notFound", "command" to root.command)
        AgentLaunchException.Kind.START_FAILED -> t("chat.agentLaunch.failed", "command" to root.command, "reason" to root.detail)
      }
    }
    return root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
  }

  /** The failure is a missing Node tool, which the person fixes on the Node interpreter's settings page */
  fun needsNodeSettings(error: Throwable): Boolean {
    val root = rootOf(error)
    return root is AgentLaunchException && root.kind == AgentLaunchException.Kind.NOT_FOUND && root.nodeTool
  }
}

/**
 * One failed request to a provider or an agent, as the thread and the log keep it.
 *
 * Kept apart from the words of the answer on purpose: a failure stored as `assistant` is read back
 * by the next request as something the model said, and a vendor's account notice then becomes
 * context for every later turn. The record names who failed, what code came back and the request
 * id where there is one — enough for a report to the vendor.
 */
object TurnFailure {
  /** ACP stop reasons that mean the request failed rather than ended; `end_turn` and `cancelled` are not among them */
  private val ABNORMAL_STOPS = setOf("refusal", "error")

  enum class Source { AGENT, PROVIDER }

  data class Record(
    val source: Source,
    /** The agent's name or the provider's id, as the person picked them */
    val name: String,
    /** `HTTP 403`, `JSON-RPC -32000`, or the stop reason; null when the failure named no code */
    val code: String? = null,
    /** The vendor's request id or the JSON-RPC request id; null when none came back */
    val requestId: String? = null,
    /** The failure's own words for the feed; never written to the log, where they may carry the person's text */
    val detail: String? = null,
  )

  /** A stop reason that means the request did not produce an answer */
  fun abnormalStop(stopReason: String?): Boolean = stopReason != null && stopReason in ABNORMAL_STOPS

  fun ofStop(agent: String, stopReason: String): Record =
    Record(Source.AGENT, agent, code = stopReason)

  fun ofAgent(agent: String, error: Throwable): Record {
    val rpc = findCause<AcpRpcError>(error)
    if (rpc != null) {
      return Record(Source.AGENT, agent, code = rpc.code?.let { "JSON-RPC $it" }, requestId = rpc.requestId,
                    detail = rpc.message?.takeIf { it.isNotBlank() })
    }
    return Record(Source.AGENT, agent, detail = AgentFailure.reasonOf(error))
  }

  fun ofProvider(provider: String, error: Throwable, requestId: String? = null): Record {
    val http = findCause<ProviderHttpException>(error)
    if (http != null) {
      return Record(Source.PROVIDER, provider, "HTTP ${http.status}", http.requestId ?: requestId, http.message)
    }
    return Record(Source.PROVIDER, provider, requestId = requestId, detail = rootMessage(error))
  }

  /** The line the feed and the thread show; it names what failed and never guesses from the words of an answer */
  fun render(record: Record): String {
    val code = record.code?.let { t("chat.failure.code", "code" to it) } ?: ""
    val request = record.requestId?.let { t("chat.failure.request", "id" to it) } ?: ""
    val line = when (record.source) {
      Source.AGENT -> t("chat.failure.agent", "agent" to record.name, "code" to code, "request" to request)
      Source.PROVIDER -> t("chat.failure.provider", "provider" to record.name, "code" to code, "request" to request)
    }
    return record.detail?.takeIf { it.isNotBlank() }?.let { line + t("chat.failure.detail", "reason" to it) } ?: line
  }

  /**
   * The line for idea.log: source, name, code and request id only.
   *
   * The failure's own message is left out on purpose — a provider body carries the person's prompt
   * back, and an agent's stderr may carry the credentials we handed it.
   */
  fun logLine(record: Record): String =
    "request failed: source=${record.source.name.lowercase()}, name=${record.name}" +
      ", code=${record.code ?: "none"}, requestId=${record.requestId ?: "none"}"

  fun log(record: Record) {
    errorLog.warn(logLine(record))
  }

  private val errorLog = logger<TurnFailure>()

  private const val CAUSE_DEPTH = 8

  private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
    var current: Throwable? = error
    repeat(CAUSE_DEPTH) {
      if (current is T) return current
      current = current?.cause
    }
    return null
  }

  /** The failure's own words, below the wrappers a future puts around it */
  private fun rootMessage(error: Throwable): String? {
    var current: Throwable = error
    repeat(CAUSE_DEPTH) {
      val cause = current.cause
      val wrapper = current is java.util.concurrent.ExecutionException ||
        current is java.util.concurrent.CompletionException ||
        current is java.lang.reflect.InvocationTargetException
      if (!wrapper || cause == null) return current.message?.takeIf { it.isNotBlank() }
      current = cause
    }
    return current.message?.takeIf { it.isNotBlank() }
  }
}
