// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * Shared subprocess primitives for hook and verify-gate execution. Kept in one
 * place because command construction is security-sensitive: the shell argv must
 * not drift between call sites, and the drain-then-timeout pattern must stay
 * identical so a hung process is always killed rather than blocking a reader.
 */
object ProcessSupport {
  /** Seconds to wait for a drain thread to reach EOF after `destroyForcibly()`. */
  const val DRAIN_JOIN_TIMEOUT_SEC = 2L

  /** Wrap a command line for the system shell (cmd.exe on Windows, /bin/sh elsewhere). */
  fun shellCommand(command: String): List<String> {
    val os = System.getProperty("os.name").lowercase()
    return if (os.contains("win")) listOf(System.getenv("COMSPEC") ?: "cmd.exe", "/d", "/s", "/c", command)
           else listOf("/bin/sh", "-c", command)
  }

  /** Read a process stream to EOF on a daemon thread so both pipes drain concurrently. */
  fun drain(stream: InputStream, threadName: String): Future<String> {
    val future = CompletableFuture<String>()
    Thread({ future.complete(runCatching { stream.bufferedReader().readText() }.getOrDefault("")) }, threadName)
      .apply { isDaemon = true }.start()
    return future
  }
}
