// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Входящее: документ без происхождения через месяц неотличим от нашего собственного текста. */
class IngestTest {
  private fun source(
    title: String = "Договор с подрядчиком",
    origin: String = "/Users/me/Downloads/contract.pdf",
    text: String = "Стороны договорились…",
    pages: Int? = 12,
    dropped: Int = 0,
  ) = Ingest.Source(title, origin, text, "2026-09-05", pages, dropped)

  @Test
  fun `шапка называет происхождение и дату`() {
    val text = Ingest.render(source())
    assertTrue(text.startsWith("# Договор с подрядчиком"))
    assertTrue("Пришло снаружи: `/Users/me/Downloads/contract.pdf`" in text)
    assertTrue("Добавлено: 2026-09-05" in text)
    assertTrue("Страниц в исходнике: 12" in text)
  }

  @Test
  fun `отрезанное пределом называется, а не прячется`() {
    assertTrue("Отрезано символов пределом: 4200" in Ingest.render(source(dropped = 4200)))
    assertTrue("Отрезано" !in Ingest.render(source()))
  }

  @Test
  fun `имя файла транслитерируется из заголовка`() {
    assertEquals("dogovor-s-podryadchikom.md", Ingest.fileName(source()))
  }

  @Test
  fun `скан без текстового слоя не кладётся`() {
    assertEquals(Ingest.Refusal.NO_TEXT, Ingest.validate(source(text = "  ")))
    assertEquals(Ingest.Refusal.NO_TITLE, Ingest.validate(source(title = "")))
    assertNull(Ingest.validate(source()))
  }

  @Test
  fun `индекс создаётся и не удваивает заголовок`() {
    val first = Ingest.appendToIndex(null, source(), "Входящее")
    assertTrue(first.startsWith("# Входящее"))
    val second = Ingest.appendToIndex(first, source(title = "Другой"), "Входящее")
    assertEquals(1, second.lines().count { it.startsWith("# ") })
    assertEquals(2, second.lines().count { it.startsWith("- [") })
  }

  @Test
  fun `заголовок из имени файла читается по-человечески`() {
    assertEquals("отчёт за квартал", Ingest.titleFromFileName("отчёт_за-квартал.pdf"))
  }
}
