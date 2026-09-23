// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The command the repository's gates run: exit codes and the count line are its contract with the scripts. */
class TextSlopCliTest {
  private class Run(val code: Int, val out: String, val err: String)

  private val catalog = assertNotNull(SlopCheck.builtIn, "the build carries no catalogue")

  private fun run(vararg args: String): Run {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = TextSlopCli.run(args.toList(), PrintStream(out, true, UTF_8), PrintStream(err, true, UTF_8))
    return Run(code, out.toString(UTF_8), err.toString(UTF_8))
  }

  private val cleanText = "Скрипт ставит инструменты и клонирует четыре репозитория. На всё уходит около одиннадцати минут.\n"

  private val sloppyText = """
    |В современном мире удалённая работа — это не просто тренд, а фундаментальная смена парадигмы.
    |
    |Наша платформа выступает в качестве единого бесшовного хаба, который выводит командную работу на новый уровень.
    |
    |Подводя итог: будущее уже здесь. И это только начало.
    |""".trimMargin()

  @Test
  fun `a clean file passes, a failing one fails the run`(@TempDir dir: Path) {
    val clean = Files.writeString(dir.resolve("clean.md"), cleanText)
    val sloppy = Files.writeString(dir.resolve("post.md"), sloppyText)
    assertEquals(0, run(clean.toString()).code)
    val failed = run(clean.toString(), sloppy.toString())
    assertEquals(1, failed.code)
    assertTrue(sloppy.toString() in failed.out)
  }

  @Test
  fun `count mode reads a directory for prose, prints the total and never fails on findings`(@TempDir dir: Path) {
    Files.createDirectories(dir.resolve("docs"))
    Files.writeString(dir.resolve("docs/post.md"), sloppyText)
    Files.writeString(dir.resolve("docs/setup.md"), cleanText)
    // Code is not prose: the same words in a source file are not counted.
    Files.writeString(dir.resolve("docs/Main.kt"), sloppyText)
    val result = run("--count", dir.toString())
    assertEquals(0, result.code)
    val expected = TextSlop.analyze(sloppyText, catalog).findings.size
    assertTrue(expected > 0)
    assertTrue(result.out.lines().contains("${TextSlopCli.COUNT_LINE}$expected"), result.out)
    assertTrue(result.out.lines().contains("$expected\t${dir.resolve("docs/post.md")}"), result.out)
  }

  @Test
  fun `an overrides file changes what is counted`(@TempDir dir: Path) {
    val post = Files.writeString(dir.resolve("post.md"), sloppyText)
    // Switching a rule off can uncover a lighter one on the same words — one problem counts once, at its heaviest —
    // so rules are switched off until nothing is left.
    val off = HashSet<String>()
    while (true) {
      val found = TextSlop.analyze(sloppyText, SlopOverrides(disable = off).applyTo(catalog) { error(it) }).findings
      if (found.isEmpty()) break
      off.addAll(found.map { it.rule })
    }
    val overrides = Files.writeString(dir.resolve("slop.json"), "{ \"disable\": [${off.joinToString(", ") { "\"$it\"" }}] }")
    val result = run("--count", "--overrides", overrides.toString(), post.toString())
    assertEquals(0, result.code)
    assertTrue(result.out.lines().contains("${TextSlopCli.COUNT_LINE}0"), result.out)
  }

  @Test
  fun `a wrong path is a broken run, never a clean one`(@TempDir dir: Path) {
    val clean = Files.writeString(dir.resolve("clean.md"), cleanText)
    val missing = dir.resolve("notes-that-are-not-there.md")
    val result = run(missing.toString())
    assertEquals(2, result.code)
    assertTrue(missing.toString() in result.err)
    assertEquals(2, run("--overrides", dir.resolve("absent.json").toString(), clean.toString()).code)
    assertEquals(2, run("--overrides", dir.toString(), clean.toString()).code, "a directory is not an overrides file")
    assertEquals(2, run("--count", missing.toString()).code)
    assertEquals(2, run().code)
  }

  @Test
  fun `a file that cannot be read breaks the count instead of lowering it`(@TempDir dir: Path) {
    Files.write(dir.resolve("broken.md"), byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte()))
    Files.writeString(dir.resolve("post.md"), sloppyText)
    val result = run("--count", dir.toString())
    assertEquals(2, result.code)
    assertTrue("broken.md" in result.err, result.err)
  }
}
