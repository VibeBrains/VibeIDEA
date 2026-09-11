// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.checkpoints

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The diff a judge gets is only as good as what it sees — a real repository, because git decides. */
class CheckpointServiceTest {
  private fun git(dir: Path, vararg args: String): Int {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
    process.inputStream.readBytes()
    return process.waitFor()
  }

  @Test
  fun `a diff against a checkpoint shows changed, new and deleted files — and only the asked ones`(@TempDir dir: Path) {
    assumeTrue(runCatching { git(dir, "init", "-q") == 0 }.getOrDefault(false), "git недоступен")
    git(dir, "config", "user.email", "test@example.com")
    git(dir, "config", "user.name", "test")
    Files.writeString(dir.resolve("kept.txt"), "было\n")
    Files.writeString(dir.resolve("gone.txt"), "удалят\n")
    git(dir, "add", "-A")
    git(dir, "commit", "-q", "-m", "init")

    val service = CheckpointService(dir.toString())
    val checkpoint = assertNotNull(service.create("перед шагом"))
    Files.writeString(dir.resolve("kept.txt"), "стало\n")
    Files.writeString(dir.resolve("new.txt"), "новый\n")
    Files.delete(dir.resolve("gone.txt"))
    Files.writeString(dir.resolve("other.txt"), "не спрашивали\n")

    val diff = assertNotNull(service.diff(checkpoint, listOf("kept.txt", "new.txt", "gone.txt")))
    assertTrue("+стало" in diff, diff)
    assertTrue("+новый" in diff, "файл, созданный после снимка, не отслеживается git — и всё равно виден: $diff")
    assertTrue("-удалят" in diff, diff)
    assertFalse("other.txt" in diff, "дифф — только по путям, которых касались шаги")
    assertEquals("", service.diff(checkpoint, emptyList()))
  }
}
