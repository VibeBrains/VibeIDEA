// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditLogTest {
  private fun logOn(base: Path): AuditLog = AuditLog(base.toString(), { true }, { 10L * 1024 * 1024 })

  private fun seed(base: Path, lines: List<String>) {
    val f = base.resolve(".vibe").resolve("audit.jsonl")
    Files.createDirectories(f.parent)
    Files.writeString(f, lines.joinToString("\n") + "\n")
  }

  @Test
  fun readRecentReturnsTail(@TempDir base: Path) {
    seed(base, (1..10).map { """{"ts":$it,"action":"prompt","ok":true}""" })
    val recent = logOn(base).readRecent(3)
    assertEquals(3, recent.size)
    assertTrue(recent.last().contains("\"ts\":10"))
    assertTrue(recent.first().contains("\"ts\":8"))
  }

  @Test
  fun readRecentEmptyWhenNoFile(@TempDir base: Path) {
    assertTrue(logOn(base).readRecent(100).isEmpty())
  }

  @Test
  fun exportCopiesLog(@TempDir base: Path) {
    seed(base, listOf("""{"ts":1,"action":"reply","ok":true}"""))
    val target = base.resolve("export.jsonl")
    logOn(base).exportTo(target)
    assertTrue(Files.isRegularFile(target))
    assertTrue(Files.readString(target).contains("reply"))
  }

  @Test
  fun deleteAllRemovesLiveAndRotated(@TempDir base: Path) {
    seed(base, listOf("""{"ts":1,"action":"prompt","ok":true}"""))
    val vibe = base.resolve(".vibe")
    Files.writeString(vibe.resolve("audit.1.jsonl.gz"), "x")
    Files.writeString(vibe.resolve("audit.2.jsonl.gz"), "y")
    // An unrelated file must NOT be deleted.
    Files.writeString(vibe.resolve("keep.txt"), "keep")
    val removed = logOn(base).deleteAll()
    assertEquals(3, removed) // live + 2 rotated
    assertFalse(Files.exists(vibe.resolve("audit.jsonl")))
    assertFalse(Files.exists(vibe.resolve("audit.1.jsonl.gz")))
    assertTrue(Files.exists(vibe.resolve("keep.txt")))
  }

  @Test
  fun `the written journal verifies as intact`(@TempDir base: Path) {
    val log = AuditLog(base.toString(), { true }, { 10L * 1024 * 1024 })
    repeat(5) { i ->
      log.append(AuditEvent(i.toLong(), AuditEvent.Action.PROMPT, ok = true, actor = AuditActor.HUMAN))
    }
    log.close()   // waits for the queued lines to land
    val verdict = log.verifyChain()
    assertTrue(verdict.intact, "цепочка должна сходиться: " + verdict)
    assertEquals(5, verdict.checked)
  }

  @Test
  fun `an edited record is caught by the chain`(@TempDir base: Path) {
    val log = AuditLog(base.toString(), { true }, { 10L * 1024 * 1024 })
    repeat(3) { i -> log.append(AuditEvent(i.toLong(), AuditEvent.Action.FS_WRITE, ok = true, actor = AuditActor.HUMAN)) }
    log.close()   // waits for the queued lines to land
    val file = base.resolve(".vibe").resolve("audit.jsonl")
    val lines = Files.readAllLines(file)
    // Someone rewrites the record of a write that failed into one that succeeded.
    Files.write(file, lines.mapIndexed { i, l -> if (i == 1) l.replace("\"ok\":true", "\"ok\":false") else l })
    assertEquals(2, log.verifyChain().brokenAtLine)
  }

  @Test
  fun `rotation does not look like tampering`(@TempDir base: Path) {
    // The first version computed the link before rotating, so the first record of the new file
    // disagreed with its own genesis and every rotation read as a forgery.
    val log = AuditLog(base.toString(), { true }, { 200L })
    repeat(6) { i -> log.append(AuditEvent(i.toLong(), AuditEvent.Action.TERMINAL, ok = true, actor = AuditActor.IDE)) }
    log.close()   // waits for the queued lines to land
    assertTrue(Files.exists(base.resolve(".vibe").resolve("audit.1.jsonl.gz")), "ротация должна была случиться")
    val verdict = log.verifyChain()
    assertTrue(verdict.intact, "живой файл после ротации обязан сходиться: " + verdict)
  }

  @Test
  fun disabledLogNeverWrites(@TempDir base: Path) {
    val log = AuditLog(base.toString(), { false }, { 10L * 1024 * 1024 })
    log.append(AuditEvent(1L, AuditEvent.Action.PROMPT, ok = true, actor = AuditActor.HUMAN))
    log.close()
    assertFalse(Files.exists(base.resolve(".vibe").resolve("audit.jsonl")))
  }
}
