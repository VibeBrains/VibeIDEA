// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import com.vibe.agent.i18n.VibeI18n.t

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
  /** Latched off after a write failure — the warning promises "off until restart", so honour it. */
  @Volatile private var disabled = false

  /**
   * The link of the last line written, so the next one continues the chain.
   *
   * Read from disk once, on the first append after a restart: the chain must survive the IDE being
   * closed, and starting a fresh chain on every launch would make «broken» the normal state.
   */
  private var lastLink: String? = null

  fun append(event: AuditEvent) {
    if (!enabled() || disabled) return
    // Serialised here, linked and written on the writer thread: the chain depends on the ORDER
    // lines reach the file, and that order is decided there — including whether a rotation happens
    // in between.
    val payload = event.toJson().toString()
    runCatching { worker.execute { writeLine(payload) } } // rejected after close() = nothing to log
  }

  /**
   * The last link on disk, or the genesis value when the journal is empty or unreadable.
   *
   * Called with [ioLock] already held — the chain and the file must not be read apart.
   */
  private fun readLastLinkLocked(): String {
    if (!Files.isRegularFile(logFile)) return AuditChain.GENESIS
    val last = runCatching { Files.readAllLines(logFile).lastOrNull { it.isNotBlank() } }.getOrNull()
      ?: return AuditChain.GENESIS
    return AuditChain.linkOf(last) ?: AuditChain.GENESIS
  }

  /**
   * Stop the writer thread, giving the lines already queued a moment to land.
   *
   * Previously the queue was simply dropped, and «best-effort» covered it — but the last records
   * before a shutdown are the ones an incident review wants most, and the chain makes a truncated
   * tail look no different from a deleted one. Bounded: a journal must never hold the IDE's exit.
   */
  fun close() {
    worker.shutdown()
    runCatching { worker.awaitTermination(CLOSE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
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

  private fun writeLine(payload: String) {
    synchronized(ioLock) {
      try {
        Files.createDirectories(logFile.parent)
        // Rotation FIRST, link second. The other order computed a link against the chain of the
        // file that was just archived, so the first record of the new file disagreed with its own
        // genesis — every rotation would have looked like tampering, which is worse than no check
        // at all: a check that cries wolf is a check people switch off.
        rotateIfNeeded(payload.toByteArray(Charsets.UTF_8).size.toLong() + LINK_OVERHEAD_BYTES)
        val previous = lastLink ?: readLastLinkLocked()
        val link = AuditChain.link(previous, payload)
        val line = payload.dropLast(1) + ",\"" + AuditChain.FIELD + "\":\"" + link + "\"}\n"
        Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        lastLink = link
      }
      catch (e: Exception) {
        disabled = true
        if (!warned) {
          warned = true
          onWarning(t("audit.writeFailed", "reason" to e.message))
        }
      }
    }
  }

  /**
   * Verifies the chain of the live journal.
   *
   * IO-bound; the caller keeps it off the EDT. Rotated archives are not walked: they are a separate
   * file with a chain of their own, and the answer «этот журнал не правили» is about this one.
   */
  fun verifyChain(): AuditChain.Verdict = synchronized(ioLock) {
    val lines = runCatching { Files.readAllLines(logFile) }.getOrDefault(emptyList())
    AuditChain.verify(lines, AuditChain::linkOf, AuditChain::withoutLink)
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
    // A rotated archive keeps its own complete chain; the live file starts a new one. Pretending
    // otherwise would make every rotation look like tampering.
    lastLink = AuditChain.GENESIS
  }

  private companion object {
    /** `,"h":"<12 hex>"}` plus the newline — counted so a rotation threshold stays a threshold. */
    const val LINK_OVERHEAD_BYTES = 22L

    /** Long enough for a queue of pending lines, short enough never to be felt on exit. */
    const val CLOSE_WAIT_MS = 500L
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
