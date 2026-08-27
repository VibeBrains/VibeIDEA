// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.terminal

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.util.Key
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes standard ACP `terminal/…` methods for NON-Claude agents (Gemini CLI
 * and others) — the default Claude adapter never calls these (it runs Bash
 * itself and streams output through `_meta.terminal_output`). Processes run via
 * the platform's [GeneralCommandLine]/[OSProcessHandler]; `kill` tears down the
 * whole process tree. Output is captured with the ACP `outputByteLimit`
 * (truncate-from-start) in [TerminalOutputBuffer].
 *
 * Not a DI service — instantiated per panel from the project base dir, mirroring
 * [com.vibe.agent.checkpoints.CheckpointService].
 */
class AgentTerminalService(private val defaultCwd: String?) {

  private class Terminal(
    val handler: OSProcessHandler,
    val buffer: TerminalOutputBuffer,
    val exitLatch: CountDownLatch,
  ) {
    @Volatile var exitCode: Int? = null
    @Volatile var signal: String? = null
    @Volatile var finished = false
  }

  private val terminals = ConcurrentHashMap<String, Terminal>()
  private var counter = 0L

  @Synchronized
  private fun nextId(): String = "vibe-term-${++counter}"

  /** Start a process; returns its terminalId. Throws on a bad command line. */
  fun create(command: String, args: List<String>, env: Map<String, String>, cwd: String?, outputByteLimit: Long?): String {
    val cmd = GeneralCommandLine(command)
      .withParameters(args)
      .withWorkingDirectory(java.nio.file.Path.of(cwd ?: defaultCwd ?: System.getProperty("user.home")))
      .withEnvironment(env)
    val handler = OSProcessHandler(cmd)
    val buffer = TerminalOutputBuffer(outputByteLimit)
    val latch = CountDownLatch(1)
    val terminal = Terminal(handler, buffer, latch)
    handler.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        buffer.append(event.text)
      }
      override fun processTerminated(event: ProcessEvent) {
        terminal.exitCode = event.exitCode
        terminal.finished = true
        latch.countDown()
      }
    })
    val id = nextId()
    terminals[id] = terminal
    handler.startNotify()
    return id
  }

  /** Non-blocking snapshot: current output, truncation flag, and exit status if finished. */
  fun output(terminalId: String): TerminalSnapshot? {
    val t = terminals[terminalId] ?: return null
    val (text, truncated) = t.buffer.snapshot()
    return TerminalSnapshot(text, truncated, if (t.finished) t.exitCode else null, t.signal, t.finished)
  }

  /** Blocks until the process exits (or the optional timeout elapses). Never call on the reader thread. */
  fun waitForExit(terminalId: String): ExitStatus? {
    val t = terminals[terminalId] ?: return null
    t.exitLatch.await()
    return ExitStatus(t.exitCode, t.signal)
  }

  /** Kill the process tree; the buffer stays readable (VibeIDE: kill ≠ release). Idempotent. */
  fun kill(terminalId: String): Boolean {
    val t = terminals[terminalId] ?: return false
    if (!t.finished) {
      runCatching { OSProcessUtil.killProcessTree(t.handler.process) }
      t.signal = "SIGKILL"
      // Unblock any waiter even if the listener is slow.
      runCatching { t.handler.destroyProcess() }
    }
    return true
  }

  /** Free the terminal; after release the id is invalid. Kills first if still running. */
  fun release(terminalId: String): Boolean {
    val t = terminals.remove(terminalId) ?: return false
    if (!t.finished) {
      runCatching { OSProcessUtil.killProcessTree(t.handler.process) }
      // Unblock any waiter even if killProcessTree threw or the listener is slow (matches kill()).
      runCatching { t.handler.destroyProcess() }
    }
    return true
  }

  fun disposeAll() {
    terminals.values.forEach { runCatching { if (!it.finished) OSProcessUtil.killProcessTree(it.handler.process) } }
    terminals.clear()
  }

  data class TerminalSnapshot(val output: String, val truncated: Boolean, val exitCode: Int?, val signal: String?, val finished: Boolean)
  data class ExitStatus(val exitCode: Int?, val signal: String?)
}
