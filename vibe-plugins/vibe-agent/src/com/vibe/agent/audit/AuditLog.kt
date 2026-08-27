// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * Append-only `.vibe/audit.jsonl` writer, VibeIDE contract:
 * off by default (a security/telemetry surface must be opt-in), one JSON object
 * per line, size-based rotation into `audit.N.jsonl.gz`. Writes run on a single
 * background thread so the reader/EDT threads that emit events never block on IO;
 * an IO failure is swallowed (a failing audit must not break the agent) but noted
 * once via [onWarning].
 *
 * Enablement is read live through [enabled] on every append, so toggling the
 * setting takes effect without recreating the log.
 */
class AuditLog(
  projectBase: String,
  private val enabled: () -> Boolean,
  private val rotationBytes: () -> Long,
  private val onWarning: (String) -> Unit = {},
) {
  private val logFile: Path = Path.of(projectBase, ".vibe", "audit.jsonl")
  private val worker = Executors.newSingleThreadExecutor { r ->
    Thread(r, "vibe-audit-writer").apply { isDaemon = true }
  }
  /** Every file touch (append, read, export, delete) takes this monitor so the log is never torn. */
  private val ioLock = Any()
  @Volatile private var warned = false
  /** Latched off after a write failure — the warning promises "отключён до перезапуска", so honour it. */
  @Volatile private var disabled = false

  fun append(event: AuditEvent) {
    if (!enabled() || disabled) return
    val line = event.toJson().toString() + "\n"
    runCatching { worker.execute { writeLine(line) } } // rejected after close() = nothing to log
  }

  /** Stop the writer thread; pending lines are dropped (best-effort audit). */
  fun close() {
    worker.shutdown()
  }

  val path: Path get() = logFile

  /**
   * Last [limit] raw JSONL lines (newest last), for the viewer. Empty when no log
   * yet. IO-bound — call OFF the EDT; the viewer marshals the result back.
   */
  fun readRecent(limit: Int): List<String> = synchronized(ioLock) {
    if (!Files.isRegularFile(logFile)) emptyList()
    else runCatching { Files.readAllLines(logFile).filter { it.isNotBlank() }.takeLast(limit) }.getOrDefault(emptyList())
  }

  /** Copy the whole live log to [target] (rotated .gz files are left in place). Call OFF the EDT. */
  fun exportTo(target: Path): Unit = synchronized(ioLock) {
    if (Files.isRegularFile(logFile)) Files.copy(logFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    else Files.writeString(target, "")
  }

  /** GDPR erase: delete the live log and every rotated segment. Returns the count removed. Call OFF the EDT. */
  fun deleteAll(): Int = synchronized(ioLock) {
    var removed = 0
    if (Files.deleteIfExists(logFile)) removed++
    val dir = logFile.parent ?: return@synchronized removed
    runCatching {
      Files.list(dir).use { stream ->
        stream.filter { it.fileName.toString().matches(Regex("audit\\.\\d+\\.jsonl\\.gz")) }.forEach {
          if (Files.deleteIfExists(it)) removed++
        }
      }
    }
    removed
  }

  private fun writeLine(line: String) {
    synchronized(ioLock) {
      try {
        Files.createDirectories(logFile.parent)
        rotateIfNeeded(line.toByteArray(Charsets.UTF_8).size.toLong())
        Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      }
      catch (e: Exception) {
        disabled = true
        if (!warned) {
          warned = true
          onWarning("аудит: запись не удалась (${e.message}) — журнал отключён до перезапуска")
        }
      }
    }
  }

  private fun rotateIfNeeded(incoming: Long) {
    val current = if (Files.isRegularFile(logFile)) Files.size(logFile) else 0L
    if (current + incoming <= rotationBytes()) return
    if (current == 0L) return
    val target = nextRotatedPath()
    val bytes = Files.readAllBytes(logFile)
    GZIPOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)).use {
      it.write(bytes)
    }
    Files.write(logFile, ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
  }

  /** First free `audit.N.jsonl.gz`, numbered from 1 (VibeIDE numeric suffix). */
  private fun nextRotatedPath(): Path {
    var n = 1
    while (true) {
      val candidate = logFile.resolveSibling("audit.$n.jsonl.gz")
      if (!Files.exists(candidate)) return candidate
      n++
    }
  }
}
