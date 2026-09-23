// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What to do with clipboard contents — one decision for both paste entry points, measured here.
 *
 * Two ways an image paste gets lost, both visible from here:
 *
 * 1. Accepting an image only when there is NO text: browsers and messengers put the image's address or caption next
 *    to it, and text would be pasted instead.
 * 2. Deciding in one entry point only (the Swing handler): wherever the keystroke does not reach it, nothing happens.
 *
 * These checks cover the priority rule and robust decoding. Whether the keystroke reaches us through both paths is
 * not checked here — that is only visible in a built IDE.
 */
class ClipboardContentTest {
  private class Clip(
    private val image: BufferedImage? = null,
    private val text: String? = null,
    private val brokenImage: Boolean = false,
  ) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = buildList {
      if (image != null || brokenImage) add(DataFlavor.imageFlavor)
      if (text != null) add(DataFlavor.stringFlavor)
    }.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

    override fun getTransferData(flavor: DataFlavor): Any = when {
      flavor == DataFlavor.imageFlavor && brokenImage -> throw java.io.IOException("буфер отдал мусор")
      flavor == DataFlavor.imageFlavor && image != null -> image
      flavor == DataFlavor.stringFlavor && text != null -> text
      else -> throw java.io.IOException("нет такого вида данных: $flavor")
    }
  }

  private fun picture(): BufferedImage = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB).also {
    val g = it.createGraphics()
    g.color = java.awt.Color.RED
    g.fillRect(0, 0, 8, 6)
    g.dispose()
  }

  @Test
  fun `картинка одна в буфере — это картинка`() {
    val kind = ClipboardContent.of(Clip(image = picture()))
    assertTrue(kind is ClipboardContent.Kind.Picture, "скриншот из буфера не опознан: $kind")
  }

  @Test
  fun `картинка рядом с текстом — всё равно картинка`() {
    val kind = ClipboardContent.of(Clip(image = picture(), text = "https://example.com/pic.png"))
    assertTrue(kind is ClipboardContent.Kind.Picture,
               "картинка отброшена из-за текста рядом с ней: $kind")
  }

  @Test
  fun `один текст — не наше дело`() {
    assertEquals(ClipboardContent.Kind.Text, ClipboardContent.of(Clip(text = "просто текст")))
  }

  @Test
  fun `пустой буфер не роняет разбор`() {
    assertEquals(ClipboardContent.Kind.Text, ClipboardContent.of(null))
  }

  @Test
  fun `битая картинка отдаёт вставку тексту, а не теряет нажатие`() {
    val kind = ClipboardContent.of(Clip(brokenImage = true, text = "запасной текст"))
    assertEquals(ClipboardContent.Kind.Text, kind,
                 "неудачный разбор обязан вернуть вставку тексту")
  }

  @Test
  fun `дешёвая проверка видит картинку и не видит текст`() {
    assertTrue(ClipboardContent.carriesOurs(Clip(image = picture(), text = "подпись")),
               "проверка по видам данных не увидела картинку")
    assertFalse(ClipboardContent.carriesOurs(Clip(text = "просто текст")),
                "проверка по видам данных приняла обычный текст за наш")
    assertFalse(ClipboardContent.carriesOurs(null), "пустой буфер не может быть нашим")
  }
}
