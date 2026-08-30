// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

import com.vibe.agent.i18n.VibeI18n.t

import com.vibe.agent.util.ProcessSupport
import java.util.concurrent.TimeUnit

/**
 * Runs the VERIFY-GATE command (tests/build/lint) and captures its verdict.
 * A launch failure (bad shell, no command) returns [ran]=false so the gate stays
 * inert — a broken configuration must never lock task completion. Output is
 * clipped to the last [OUTPUT_TAIL] chars, VibeIDE-style.
 */
class VerifyGateRunner(private val cwd: String?) {

  data class VerifyResult(val ran: Boolean, val passed: Boolean, val exitCode: Int?, val outputTail: String)

  fun run(command: String, timeoutMs: Int, cancelled: () -> Boolean = { false }): VerifyResult {
    if (command.isBlank() || cwd == null) return VerifyResult(ran = false, passed = false, exitCode = null, outputTail = "")
    return try {
      val pb = ProcessBuilder(ProcessSupport.shellCommand(command))
      pb.directory(java.io.File(cwd))
      pb.redirectErrorStream(true)
      val process = pb.start()
      // Drain output on a separate thread and bound the wait FIRST — reading to EOF on the current
      // thread would block until the process exits, making the timeout unreachable for a hung command.
      val outFuture = ProcessSupport.drain(process.inputStream, "vibe-verify-drain")
      // Wait in slices so a user's Stop can abort a minutes-long build promptly.
      var waited = 0L
      var finished = false
      while (waited < timeoutMs) {
        if (process.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) { finished = true; break }
        waited += CANCEL_POLL_MS
        if (cancelled()) {
          runCatching { com.intellij.execution.process.OSProcessUtil.killProcessTree(process) }
          process.destroyForcibly()
          // Cancelled = the gate never ran to a verdict; inert, not "red".
          return VerifyResult(ran = false, passed = false, exitCode = null, outputTail = "")
        }
      }
      if (!finished) {
        // Reap the whole tree, not just the shell child — a build daemon grandchild would otherwise
        // keep the pipe open, hang the drain thread and starve the output.
        runCatching { com.intellij.execution.process.OSProcessUtil.killProcessTree(process) }
        process.destroyForcibly()
      }
      val output = runCatching { outFuture.get(ProcessSupport.DRAIN_JOIN_TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrDefault("")
      if (!finished) return VerifyResult(ran = true, passed = false, exitCode = null, outputTail = tail(output) + "\n[" + t("gates.timeout") + "]")
      val code = process.exitValue()
      VerifyResult(ran = true, passed = code == 0, exitCode = code, outputTail = tail(output))
    }
    catch (e: Exception) {
      // Launch failure = inert gate, never "red".
      VerifyResult(ran = false, passed = false, exitCode = null, outputTail = "")
    }
  }

  private fun tail(s: String): String = if (s.length > OUTPUT_TAIL) "…" + s.takeLast(OUTPUT_TAIL) else s

  companion object {
    const val OUTPUT_TAIL = 8000
    /** Slice length for the cancellable wait loop. */
    const val CANCEL_POLL_MS = 1000L
  }
}
