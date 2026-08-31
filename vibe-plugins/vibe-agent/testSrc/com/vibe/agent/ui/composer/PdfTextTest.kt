// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfTextTest {
  @Test
  fun `pdf опознаётся по подписи, а не по расширению`() {
    val header = "%PDF".toByteArray()
    assertTrue(PdfText.looksLikePdf("отчёт.pdf", header))
    assertFalse(PdfText.looksLikePdf("отчёт.pdf", "%ZIP".toByteArray()), "расширение — заявление, а не факт")
    assertFalse(PdfText.looksLikePdf("отчёт.txt", header))
    assertFalse(PdfText.looksLikePdf("пусто.pdf", ByteArray(0)))
  }

  @Test
  fun `перенос по слогам склеивается обратно`() {
    // Иначе поиск фразы внутри документа проваливается ровно на тех фразах, что попали на разрыв.
    assertEquals("документация", PdfText.clean("документа-\nция"))
    assertEquals("слово\nдругое", PdfText.clean("слово\nдругое"))
  }

  @Test
  fun `лишние пустые строки и хвосты пробелов убираются`() {
    assertEquals("первый\n\nвторой", PdfText.clean("  первый  \n\n\n\n  второй  \n"))
  }

  @Test
  fun `скан узнаётся по количеству текста на страницу`() {
    assertTrue(PdfText.looksScanned("1\n2\n3", pages = 10), "три цифры на десять страниц — это скан")
    assertFalse(PdfText.looksScanned("а".repeat(50 * 10), pages = 10))
    assertTrue(PdfText.looksScanned("", pages = 0), "нет страниц и нет текста — читать нечего")
  }

  @Test
  fun `обрезка сообщает, сколько отброшено`() {
    val long = "я".repeat(PdfText.MAX_CHARS + 1_234)
    val trimmed = PdfText.trim(long)
    assertEquals(PdfText.MAX_CHARS, trimmed.text.length)
    assertEquals(1_234, trimmed.droppedChars)
    assertTrue(trimmed.wasTrimmed)
  }

  @Test
  fun `короткий документ не трогается`() {
    val trimmed = PdfText.trim("немного текста")
    assertEquals("немного текста", trimmed.text)
    assertFalse(trimmed.wasTrimmed)
  }

  @Test
  fun `нечитаемый файл возвращает названную ошибку, а не пустоту`() {
    val missing = java.io.File("/несуществующий/путь/файл.pdf")
    val result = PdfExtract.read(missing)
    assertTrue(result.isFailure, "пустая строка уехала бы модели как документ, в котором ничего нет")
  }

  @Test
  fun `не-pdf с расширением pdf опознаётся как не-pdf`() {
    val file = java.io.File.createTempFile("vibe-pdf", ".pdf")
    try {
      file.writeText("это обычный текст, а не документ")
      assertFalse(Attachments.isPdfFile(file))
      assertTrue(PdfExtract.read(file).isFailure)
    }
    finally {
      file.delete()
    }
  }
}
