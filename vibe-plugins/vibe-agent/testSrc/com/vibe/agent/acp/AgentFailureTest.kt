// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.providers.ProviderHttpException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the person reads when a request to an agent fails: words, and not the class name of an exception. */
class AgentFailureTest {
  private val stopped = AgentGoneException(AgentGoneException.Kind.STOPPED, "agent stopped")

  @Test
  fun `a stopped agent reads as a stopped agent, with what to do, and without a class name`() {
    // The wrapper a future waited with get() puts around the failure: its message is cause.toString()
    val wrapped = ExecutionException(stopped)
    assertTrue("Exception" in wrapped.message!!, "the wrapper does carry a class name in its message: ${wrapped.message}")
    val text = AgentFailure.reasonOf(wrapped)
    assertTrue(text.startsWith("агент остановлен"), text)
    assertFalse("Exception" in text || "agent stopped" in text, text)
    assertTrue("ещё раз" in text, "it says what to do: $text")
  }

  @Test
  fun `a failure reads the same whichever wrapper it comes in`() {
    val bare = AgentFailure.reasonOf(stopped)
    assertEquals(bare, AgentFailure.reasonOf(ExecutionException(stopped)))
    assertEquals(bare, AgentFailure.reasonOf(CompletionException(stopped)))
    assertEquals(bare, AgentFailure.reasonOf(CompletionException(ExecutionException(stopped))))
  }

  @Test
  fun `a failed future of the client reads as words after real wrapping`() {
    val future = CompletableFuture<String>().also { it.completeExceptionally(stopped) }
    val failure = assertFailsWith<ExecutionException> { future.get() }
    assertTrue("Exception" in failure.message!!, "the wrapper does carry a class name: ${failure.message}")
    assertTrue(AgentFailure.reasonOf(failure).startsWith("агент остановлен"))
    val derived = future.thenApply { it }.handle { _, error -> error }.get()
    assertTrue(AgentFailure.reasonOf(derived).startsWith("агент остановлен"), "a derived future wraps in CompletionException")
  }

  @Test
  fun `a process that ended names its exit code, and a broken pipe names what the system said`() {
    val gone = AgentGoneException(AgentGoneException.Kind.EXITED, "agent process exited (code 137)", "137")
    val exited = AgentFailure.reasonOf(ExecutionException(gone))
    assertTrue("137" in exited && "процесс агента" in exited, exited)
    val broken = AgentGoneException(AgentGoneException.Kind.PIPE_CLOSED, "agent pipe closed: Broken pipe", "Broken pipe")
    val pipe = AgentFailure.reasonOf(broken)
    assertTrue("Broken pipe" in pipe && "связь с агентом" in pipe, pipe)
  }

  @Test
  fun `the diagnostic message of the exception is what it always was`() {
    assertEquals("agent stopped", stopped.message)
    assertTrue(stopped is IllegalStateException, "code that catches IllegalStateException still does")
    assertEquals(AgentGoneException.Kind.STOPPED, stopped.kind)
  }

  @Test
  fun `any other failure keeps its own message, below the wrappers`() {
    assertEquals("Cannot run program \"agent\"", AgentFailure.reasonOf(ExecutionException(IOException("Cannot run program \"agent\""))))
    assertEquals("boom", AgentFailure.reasonOf(IllegalArgumentException("boom")))
  }

  @Test
  fun `a failure without a message is named by its class and not by the word null`() {
    assertEquals("IOException", AgentFailure.reasonOf(ExecutionException(IOException())))
    assertEquals("IllegalStateException", AgentFailure.reasonOf(IllegalStateException("  ")))
  }

  @Test
  fun `the wrappers are taken off down to the failure, and a very deep pile of them still ends`() {
    assertSame(stopped, AgentFailure.rootOf(CompletionException(ExecutionException(stopped))))
    var pile: Throwable = stopped
    repeat(50) { pile = ExecutionException(pile) }
    assertTrue(AgentFailure.reasonOf(pile).isNotBlank())
  }

  @Test
  fun `only a refusal or an error stop is an abnormal end of a turn`() {
    assertTrue(TurnFailure.abnormalStop("refusal"))
    assertTrue(TurnFailure.abnormalStop("error"))
    assertFalse(TurnFailure.abnormalStop("end_turn"))
    assertFalse(TurnFailure.abnormalStop("cancelled"))
    assertFalse(TurnFailure.abnormalStop(null))
  }

  @Test
  fun `an agent rpc failure keeps the json-rpc code and the request id`() {
    val failure = TurnFailure.ofAgent("Claude Code",
      ExecutionException(AcpRpcError(-32000, "Authentication required", "7")))

    assertEquals("JSON-RPC -32000", failure.code)
    assertEquals("7", failure.requestId)
    val text = TurnFailure.render(failure)
    assertTrue("Claude Code" in text && "-32000" in text && "7" in text, text)
  }

  @Test
  fun `an abnormal stop names the agent and the reason it gave`() {
    val failure = TurnFailure.ofStop("Claude Code", "refusal")

    assertEquals(TurnFailure.Source.AGENT, failure.source)
    assertEquals("refusal", failure.code)
    assertTrue("Claude Code" in TurnFailure.render(failure), TurnFailure.render(failure))
  }

  @Test
  fun `a provider http failure keeps the status and the request id`() {
    val error = ExecutionException(ProviderHttpException(403, "req-1", "your account is on hold"))
    val failure = TurnFailure.ofProvider("openai", error)

    assertEquals("HTTP 403", failure.code)
    assertEquals("req-1", failure.requestId)
    assertTrue("openai" in TurnFailure.render(failure), TurnFailure.render(failure))
  }

  @Test
  fun `the log line names the provider, the code and the request id, and no message text`() {
    val failure = TurnFailure.ofProvider("openai",
      ProviderHttpException(403, "req-1", "your account is on hold: secret prompt"))

    val line = TurnFailure.logLine(failure)
    assertTrue("provider" in line && "openai" in line && "HTTP 403" in line && "req-1" in line, line)
    assertFalse("on hold" in line || "secret" in line || "HTTP 403:" in line, line)
  }
}
