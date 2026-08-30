// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodeTest {
  @Test
  fun `адрес кодируется, и в коде есть тёмные модули`() {
    val bits = assertNotNull(QrCode.matrix("http://192.168.1.42:3000/app"))
    assertEquals(bits.width, bits.height, "код квадратный")
    var dark = 0
    for (y in 0 until bits.height) for (x in 0 until bits.width) if (bits.get(x, y)) dark++
    // Примерно половина модулей тёмная у любого QR; проверяем, что это код, а не пустой квадрат.
    assertTrue(dark > bits.width * bits.height / 10, "тёмных модулей $dark — это не похоже на код")
  }

  @Test
  fun `пустой адрес кодировать нечего`() {
    assertNull(QrCode.matrix("   "))
    assertNull(QrCode.image(""))
  }

  @Test
  fun `тихая зона на месте — без неё код читается ненадёжно`() {
    val bits = assertNotNull(QrCode.matrix("http://10.0.0.5:5173"))
    assertTrue(!bits.get(0, 0), "внешний модуль светлый: это поле, а не данные")
  }

  @Test
  fun `картинка чёрно-белая независимо от темы IDE`() {
    val image = assertNotNull(QrCode.image("http://192.168.1.42:3000"))
    val corner = image.getRGB(0, 0)
    assertEquals(java.awt.Color.WHITE.rgb, corner, "поле кода белое, иначе камера его не отделит")
    assertTrue(image.width > 0 && image.width == image.height)
  }

  @Test
  fun `длинный адрес всё ещё кодируется`() {
    val long = "http://192.168.100.200:8443/" + "path/".repeat(30)
    assertNotNull(QrCode.matrix(long))
  }
}
