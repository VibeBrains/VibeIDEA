// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Переезд рантайма в `.vibe/local/`.
 *
 * Главное здесь — не новое место, а то, что старое **не бросается**: чекпоинты это то, чем работает
 * `/undo`, а журнал аудита не восстанавливается ничем. «Начнём с чистого листа» в этих двух файлах
 * означает тихую потерю, которую человек заметит в тот день, когда она будет стоить дороже всего.
 */
class VibeLocalTest {
  private fun project(): Path = Files.createTempDirectory("vibe-local-test")

  private fun vibe(base: Path): Path = base.resolve(".vibe").also { it.createDirectories() }

  @Test
  fun `в чистом проекте файл сразу заводится в local`() {
    val base = project()
    vibe(base)
    val file = VibeLocal.file(base.toString(), "audit.jsonl")
    assertEquals(base.resolve(".vibe/local/audit.jsonl"), file)
    assertTrue(Files.isDirectory(file.parent), "каталог обязан быть создан до первой записи")
  }

  @Test
  fun `уже лежащий файл переносится вместе с содержимым`() {
    val base = project()
    val dir = vibe(base)
    dir.resolve("checkpoints.jsonl").writeText("""{"id":"c1"}""")

    val file = VibeLocal.file(base.toString(), "checkpoints.jsonl")

    assertEquals(base.resolve(".vibe/local/checkpoints.jsonl"), file)
    assertEquals("""{"id":"c1"}""", file.readText(), "содержимое обязано доехать")
    assertFalse(Files.exists(dir.resolve("checkpoints.jsonl")), "копия на старом месте вводила бы в заблуждение")
  }

  @Test
  fun `новое место сильнее старого и перезаписи не происходит`() {
    val base = project()
    val dir = vibe(base)
    dir.resolve("audit.jsonl").writeText("старое")
    VibeLocal.dir(base.toString()).createDirectories()
    VibeLocal.dir(base.toString()).resolve("audit.jsonl").writeText("новое")

    val file = VibeLocal.file(base.toString(), "audit.jsonl")

    // Обратный порядок затёр бы журнал, который уже пишется в новом месте.
    assertEquals("новое", file.readText())
    assertEquals("старое", dir.resolve("audit.jsonl").readText(), "старый файл не трогаем, раз новый уже есть")
  }

  @Test
  fun `архивы журнала переезжают вместе с ним`() {
    val base = project()
    val dir = vibe(base)
    dir.resolve("audit.2026-09-01.jsonl.gz").writeText("архив")
    dir.resolve("audit.jsonl").writeText("свежий")
    dir.resolve("providers.json").writeText("не трогать")

    VibeLocal.file(base.toString(), "audit.jsonl")
    VibeLocal.migrateSiblings(base.toString(), "audit.", ".jsonl.gz")

    val local = VibeLocal.dir(base.toString())
    assertEquals("архив", local.resolve("audit.2026-09-01.jsonl.gz").readText())
    // Журнал без архивов отвечает на «что было неделю назад» тишиной — худший из ответов.
    assertFalse(Files.exists(dir.resolve("audit.2026-09-01.jsonl.gz")))
    assertEquals("не трогать", dir.resolve("providers.json").readText(), "конфиги не рантайм")
  }

  @Test
  fun `ключи остаются на месте`() {
    val base = project()
    val dir = vibe(base)
    dir.resolve(".env").writeText("ANTHROPIC_API_KEY=xxx")

    VibeLocal.file(base.toString(), "audit.jsonl")

    // .env пишет человек, а не IDE. Переезд означал бы молчаливую потерю ключей у всех, у кого он
    // уже лежит: провайдеры «сломались» бы без единого сообщения.
    assertTrue(Files.exists(dir.resolve(".env")))
  }
}
