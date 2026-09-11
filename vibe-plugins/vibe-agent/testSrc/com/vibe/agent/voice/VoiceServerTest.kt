// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.voice

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The resident server's protocol, against a stand-in on the loopback interface. */
class VoiceServerTest {
  private val model = "/models/ggml-base-q5_1.bin"
  private val binary = "/opt/homebrew/bin/whisper-server"
  private val lookup: (String) -> String? = { if (it == VoiceServer.BINARY) binary else null }

  @Test
  fun `a live transcript needs both the server and a model file`() {
    assertEquals(VoiceServer.Config(binary, model, "ru"), VoiceServer.configOf(model, " ru ", lookup) { it == model })
    assertEquals("auto", VoiceServer.configOf(model, "", lookup) { it == model }?.language,
                 "без языка whisper.cpp решил бы, что речь английская")
    assertNull(VoiceServer.configOf("", "ru", lookup) { true })
    assertNull(VoiceServer.configOf(model, "ru", { null }) { true })
  }

  @Test
  fun `the server listens on the loopback interface only`() {
    assertEquals(listOf("whisper-server", "-m", model, "--host", "127.0.0.1", "--port", "43210", "-nt", "-l", "ru"),
                 VoiceServer.command(VoiceServer.Config("whisper-server", model, "ru"), 43210))
  }

  @Test
  fun `the form carries the audio as file and asks for json`() {
    val body = String(VoiceServer.multipart("B", byteArrayOf(1, 2, 3)), Charsets.ISO_8859_1)
    assertTrue("name=\"response_format\"\r\n\r\njson" in body, body)
    val audio = String(byteArrayOf(1, 2, 3), Charsets.ISO_8859_1)
    assertTrue("name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n$audio\r\n--B--" in body, body)
  }

  @Test
  fun `the answer's text is taken trimmed, anything else is no text`() {
    assertEquals("проверка голосового ввода", VoiceServer.textOf("{\"text\":\" проверка голосового ввода\\n\"}"))
    assertNull(VoiceServer.textOf("{\"error\":\"bad\"}"))
    assertNull(VoiceServer.textOf("Invalid request"))
  }

  @Test
  fun `a request goes out as the form and its answer comes back as text`() {
    var received = ByteArray(0)
    var contentType = ""
    val fake = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    fake.createContext("/") { exchange ->
      val answer = if (exchange.requestURI.path == "/inference") {
        received = exchange.requestBody.readBytes()
        contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
        "{\"text\":\" привет\\n\"}"
      }
      else "<html></html>"
      val bytes = answer.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    fake.start()
    try {
      val port = fake.address.port
      assertTrue(VoiceServer.answers(port), "страница на / — признак, что модель загружена")
      assertEquals("привет", VoiceServer.post(port, byteArrayOf(9, 8, 7), 5_000))
      assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType)
      assertTrue(String(byteArrayOf(9, 8, 7), Charsets.ISO_8859_1) in String(received, Charsets.ISO_8859_1), "аудио уходит в теле формы")
    }
    finally {
      fake.stop(0)
    }
  }

  @Test
  fun `a refusal is an error, not an empty transcript`() {
    val fake = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    fake.createContext("/inference") { exchange ->
      val bytes = "Invalid request".toByteArray()
      exchange.sendResponseHeaders(400, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    fake.start()
    try {
      assertFailsWith<IllegalStateException> { VoiceServer.post(fake.address.port, byteArrayOf(1), 5_000) }
      assertFalse(VoiceServer.answers(fake.address.port), "без страницы на / сервер ещё не готов")
    }
    finally {
      fake.stop(0)
    }
  }

  @Test
  fun `the capture becomes a WAV the transcribers read`() {
    // One second of 16 kHz mono 16-bit silence.
    val wav = VoiceCapture.wav(ByteArray(32_000))
    assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
    val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16)
    assertEquals(16_000, rate)
    assertEquals(32_000 + 44, wav.size)
  }
}
