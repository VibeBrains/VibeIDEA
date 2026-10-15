// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.i18n.VibeI18n.t
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
