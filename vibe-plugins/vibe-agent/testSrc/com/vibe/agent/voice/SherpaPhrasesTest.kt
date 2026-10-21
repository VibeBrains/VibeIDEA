// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.voice.VoiceEngine.LiveText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phrase logic of local recognition with scripted decoders: drafts while a phrase is spoken, the final text when it ends,
 * and the choice of the native libraries a platform folder holds
 */
class SherpaPhrasesTest {
  /** A streaming decoder that answers [drafts] one per accepted chunk, and hears the end of a phrase where [ends] says */
  private class Script(private val drafts: List<String>, private val ends: Set<Int> = emptySet()) : StreamingDecoder {
    var chunks = 0
    var resets = 0
    val heard = mutableListOf<Int>()

    override fun accept(samples: FloatArray) {
      heard += samples.size
      chunks++
    }

    override fun text(): String = drafts.getOrElse(chunks - 1) { drafts.lastOrNull().orEmpty() }
    override fun endpoint(): Boolean = chunks in ends
    override fun reset() { resets++ }
  }

  private val said = mutableListOf<LiveText>()
  private val rate = 16_000

  @Test
  fun `drafts are said once each as they change, and the end of a phrase gives the final model's text`() {
    val streaming = Script(listOf("п", "привет", "привет", "привет ми"), ends = setOf(4))
    val decoded = mutableListOf<Int>()
    val splitter = PhraseSplitter(streaming, { samples -> decoded += samples.size; "привет мир" }, rate) { said += it }
    repeat(4) { splitter.accept(FloatArray(100)) }
    assertEquals(listOf(LiveText.Draft("п"), LiveText.Draft("привет"), LiveText.Draft("привет ми"), LiveText.Phrase("привет мир")), said)
    assertEquals(listOf(400), decoded, "the final model hears the whole phrase, every chunk of it")
    assertEquals(1, streaming.resets)
  }

  @Test
  fun `the stop settles the streaming model with silence that is not part of the phrase, and ends the last phrase`() {
    val streaming = Script(listOf("готово"))
    val decoded = mutableListOf<Int>()
    val splitter = PhraseSplitter(streaming, { samples -> decoded += samples.size; "готово" }, rate) { said += it }
    splitter.accept(FloatArray(800))
    assertEquals(listOf("готово"), splitter.flush())
    assertEquals((PhraseSplitter.TAIL_SILENCE_SECONDS * rate).toInt(), streaming.heard.last())
    assertEquals(listOf(800), decoded)
  }

  @Test
  fun `without a final model the last draft is the phrase, and an empty final text falls back to it too`() {
    val alone = PhraseSplitter(Script(listOf("hello world"), ends = setOf(1)), null, rate) { said += it }
    alone.accept(FloatArray(10))
    assertEquals(LiveText.Phrase("hello world"), said.last())
    said.clear()
    val silentFinal = PhraseSplitter(Script(listOf("слово"), ends = setOf(1)), { "" }, rate) { said += it }
    silentFinal.accept(FloatArray(10))
    assertEquals(LiveText.Phrase("слово"), said.last())
  }

  @Test
  fun `silence that ends a phrase with nothing heard says nothing`() {
    val splitter = PhraseSplitter(Script(listOf(""), ends = setOf(1)), { "" }, rate) { said += it }
    splitter.accept(FloatArray(10))
    assertTrue(said.isEmpty())
    assertEquals(emptyList(), splitter.flush())
  }

  @Test
  fun `a monologue is cut into phrases at the cap even when no pause comes`() {
    // The text grows every second, as it does while somebody speaks without a break
    val streaming = Script(List(PhraseSplitter.MAX_PHRASE_SECONDS) { "слово ".repeat(it + 1) })
    val splitter = PhraseSplitter(streaming, { "кусок" }, rate) { said += it }
    val second = FloatArray(rate)
    repeat(PhraseSplitter.MAX_PHRASE_SECONDS - 1) { splitter.accept(second) }
    assertEquals(0, streaming.resets, "no pause, no cap yet")
    splitter.accept(second)
    assertEquals(LiveText.Phrase("кусок"), said.last())
    assertEquals(1, streaming.resets)
  }

  @Test
  fun `a pause after speech ends the phrase even when the model never reports an endpoint, as T-one does not`() {
    val streaming = Script(listOf("открой", "открой файл", "открой файл", "открой файл", "открой файл"))
    val splitter = PhraseSplitter(streaming, { "открой файл" }, rate) { said += it }
    val tenth = FloatArray(rate / 10)
    splitter.accept(tenth)
    splitter.accept(tenth)
    val pause = FloatArray((PhraseSplitter.ENDPOINT_SILENCE_SECONDS * rate).toInt() / 2)
    splitter.accept(pause)
    assertTrue(said.none { it is LiveText.Phrase }, "half the pause is not the end yet")
    splitter.accept(pause)
    assertEquals(LiveText.Phrase("открой файл"), said.last())
    assertEquals(1, streaming.resets)
  }

  @Test
  fun `a long stretch with nothing heard closes no phrase and keeps only the lead-in`() {
    val streaming = Script(listOf(""))
    val decoded = mutableListOf<Int>()
    val splitter = PhraseSplitter(streaming, { samples -> decoded += samples.size; "" }, rate) { said += it }
    repeat(PhraseSplitter.IDLE_SILENCE_SECONDS.toInt() * 2) { splitter.accept(FloatArray(rate)) }
    assertEquals(0, streaming.resets, "the stream is not reset by silence")
    assertTrue(said.isEmpty())
    splitter.flush()
    assertTrue(decoded.all { it <= (PhraseSplitter.IDLE_SILENCE_SECONDS * rate).toInt() }, "the buffer was trimmed: $decoded")
  }

  @Test
  fun `speech that starts just before the idle mark keeps its first word`() {
    for (before in listOf(0.4, 0.6, 0.9)) {
      said.clear()
      val begins = ((PhraseSplitter.IDLE_SILENCE_SECONDS - before) * 10).toInt()
      // The model shows the word a second after it began, as T-one does
      val drafts = List(100) { if (it < begins + 10) "" else "запусти" }
      val streaming = Script(drafts)
      val decoded = mutableListOf<Int>()
      val splitter = PhraseSplitter(streaming, { samples -> decoded += samples.size; "запусти" }, rate) { said += it }
      repeat(begins + 14) { splitter.accept(FloatArray(rate / 10)) }
      assertEquals(0, streaming.resets, "silence before $before s did not reset the stream")
      assertEquals(LiveText.Draft("запусти"), said.last())
      assertEquals(listOf("запусти"), splitter.flush())
      assertTrue(decoded.single() >= ((before + 1.0) * rate).toInt(), "the audio of the word is in the phrase: $decoded")
    }
  }

  @Test
  fun `the platform folders are named as sherpa-onnx names its native jars`() {
    assertEquals("osx-aarch64", SherpaNative.platformOf("Mac OS X", "aarch64"))
    assertEquals("osx-x64", SherpaNative.platformOf("Mac OS X", "x86_64"))
    assertEquals("win-x64", SherpaNative.platformOf("Windows 11", "amd64"))
    assertEquals("win-arm64", SherpaNative.platformOf("Windows 11", "aarch64"))
    assertEquals("linux-x64", SherpaNative.platformOf("Linux", "amd64"))
    assertEquals(null, SherpaNative.platformOf("SunOS", "sparc"))
  }

  @Test
  fun `the runtime is loaded before the JNI library, and found by its prefix whatever its version`() {
    val mac = { name: String -> "lib$name.dylib" }
    assertEquals(
      listOf("libonnxruntime.1.27.0.dylib", "libsherpa-onnx-jni.dylib"),
      SherpaNative.libraries(listOf("README.txt", "libsherpa-onnx-jni.dylib", "libonnxruntime.1.27.0.dylib", "sherpa-onnx-LICENSE"), mac),
    )
    val win = { name: String -> "$name.dll" }
    assertEquals(
      listOf("onnxruntime.dll", "sherpa-onnx-jni.dll"),
      SherpaNative.libraries(listOf("onnxruntime_providers_shared.dll", "onnxruntime.dll", "sherpa-onnx-jni.dll"), win),
    )
    assertEquals(null, SherpaNative.libraries(listOf("libsherpa-onnx-jni.dylib"), mac), "a JNI library without its runtime is not loadable")
    assertEquals(null, SherpaNative.libraries(listOf("libonnxruntime.1.27.0.dylib"), mac))
  }
}
