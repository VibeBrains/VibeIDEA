// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.voice.WhisperModels.Candidate
import com.vibe.agent.voice.WhisperModels.Resolved
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which model a machine's folders give the field «whisper.cpp model» when the person left it empty. */
class WhisperModelsTest {
  private val mib = 1024L * 1024

  private fun model(name: String, mebibytes: Long, dir: String = "/models") = Candidate("$dir/$name", mebibytes * mib)

  @Test
  fun `the folders are the cache of whisper-cpp and the two prefixes of Homebrew`() {
    val folders = WhisperModels.folders("/Users/x").map { it.path }
    assertEquals(listOf("/Users/x/.cache/whisper.cpp", "/opt/homebrew/share/whisper-cpp", "/usr/local/share/whisper-cpp"), folders)
  }

  @Test
  fun `the layout of the owner's machine gives the base model and not the test model that Homebrew ships beside it`() {
    // Sizes are the real ones: ggml-base-q5_1.bin is 59 707 625 bytes, for-tests-ggml-tiny.bin is 575 451
    val found = listOf(
      Candidate("/Users/x/.cache/whisper.cpp/ggml-base-q5_1.bin", 59_707_625),
      Candidate("/opt/homebrew/share/whisper-cpp/for-tests-ggml-tiny.bin", 575_451),
    )
    assertEquals("/Users/x/.cache/whisper.cpp/ggml-base-q5_1.bin", WhisperModels.pick(found, "ru")?.path)
  }

  @Test
  fun `a file with the name of a model but the size of a test fixture is not a model`() {
    assertFalse(WhisperModels.isModel(Candidate("/m/ggml-tiny.bin", 575_451)))
    assertFalse(WhisperModels.isModel(Candidate("/m/ggml-tiny.bin", WhisperModels.MIN_MODEL_BYTES - 1)))
    assertTrue(WhisperModels.isModel(Candidate("/m/ggml-tiny.bin", WhisperModels.MIN_MODEL_BYTES)))
  }

  @Test
  fun `only ggml bin files are models, and the voice-activity and encoder files that wear the prefix are not`() {
    assertFalse(WhisperModels.isModel(model("whisper-base.bin", 100)))
    assertFalse(WhisperModels.isModel(model("ggml-base.gguf", 100)))
    assertFalse(WhisperModels.isModel(model("for-tests-ggml-tiny.bin", 100)))
    assertFalse(WhisperModels.isModel(model("ggml-silero-v5.1.2.bin", 100)), "the voice-activity model is not a speech model")
    assertFalse(WhisperModels.isModel(model("ggml-base-encoder-openvino.bin", 100)))
    assertTrue(WhisperModels.isModel(model("GGML-Base.BIN", 100)), "the names are matched without regard to case")
  }

  @Test
  fun `English-only names are told by the dot-en part and by nothing else`() {
    listOf("ggml-base.en.bin", "ggml-base.en-q5_1.bin", "ggml-medium.en-q8_0.bin", "ggml-tiny.en.bin")
      .forEach { assertTrue(WhisperModels.englishOnly(model(it, 100)), it) }
    listOf("ggml-base.bin", "ggml-base-q5_1.bin", "ggml-large-v3-turbo.bin", "ggml-large-v3-turbo-q5_0.bin", "ggml-small.bin")
      .forEach { assertFalse(WhisperModels.englishOnly(model(it, 100)), it) }
  }

  @Test
  fun `a multilingual model is preferred to an English-only one even when the English one is smaller`() {
    val models = listOf(model("ggml-base.en.bin", 74), model("ggml-small.bin", 466))
    assertEquals("ggml-small.bin", WhisperModels.pick(models, "ru")?.name)
    assertEquals("ggml-small.bin", WhisperModels.pick(models, "en")?.name, "multilingual first, in English too")
  }

  @Test
  fun `among multilingual models the smaller wins, so the first transcription starts sooner`() {
    val models = listOf(model("ggml-medium.bin", 1500), model("ggml-base-q5_1.bin", 57), model("ggml-small.bin", 466))
    assertEquals("ggml-base-q5_1.bin", WhisperModels.pick(models, "ru")?.name)
  }

  @Test
  fun `equal sizes are decided by the path, so the answer does not depend on the order of the folders`() {
    val a = model("ggml-base.bin", 142, dir = "/a")
    val b = model("ggml-base.bin", 142, dir = "/b")
    assertEquals(a, WhisperModels.pick(listOf(b, a), "ru"))
    assertEquals(a, WhisperModels.pick(listOf(a, b), "ru"))
  }

  @Test
  fun `an English-only model is for English alone, because fed Russian it answers with confident nonsense`() {
    val onlyEnglish = listOf(model("ggml-base.en.bin", 142))
    assertNull(WhisperModels.pick(onlyEnglish, "ru"))
    assertNull(WhisperModels.pick(onlyEnglish, ""), "no hint is not English")
    assertEquals("ggml-base.en.bin", WhisperModels.pick(onlyEnglish, "en")?.name)
    assertEquals("ggml-base.en.bin", WhisperModels.pick(onlyEnglish, " EN ")?.name)
  }

  @Test
  fun `no candidates, no model`() {
    assertNull(WhisperModels.pick(emptyList(), "ru"))
    assertNull(WhisperModels.pick(listOf(model("notes.txt", 100)), "ru"))
  }

  @Test
  fun `the person's own path wins even over a found model, and a wrong one is named instead of replaced`() {
    val found = { _: String -> model("ggml-base-q5_1.bin", 57) }
    val has = { path: String -> path == "/mine/ggml-tiny.bin" }
    assertEquals(Resolved.Manual("/mine/ggml-tiny.bin"), WhisperModels.resolve(" /mine/ggml-tiny.bin ", "ru", has, found))
    assertEquals(Resolved.ManualMissing("/mine/typo.bin"), WhisperModels.resolve("/mine/typo.bin", "ru", has, found))
  }

  @Test
  fun `an empty field takes the found model, and says none when there is none`() {
    val has = { _: String -> false }
    assertEquals(Resolved.Found("/models/ggml-base-q5_1.bin"), WhisperModels.resolve("", "ru", has) { model("ggml-base-q5_1.bin", 57) })
    assertEquals(Resolved.Found("/models/ggml-base-q5_1.bin"), WhisperModels.resolve(null, "ru", has) { model("ggml-base-q5_1.bin", 57) })
    assertEquals(Resolved.None, WhisperModels.resolve("  ", "ru", has) { null })
  }

  @Test
  fun `the language of the settings reaches the search`() {
    var asked: String? = null
    WhisperModels.resolve("", "en", { false }) { language -> asked = language; null }
    assertEquals("en", asked)
  }

  @Test
  fun `scanning real folders lists files one level deep and shrugs at a folder that is not there`() {
    val root = Files.createTempDirectory("vibe-models-").toFile()
    try {
      val cache = File(root, "cache").apply { mkdirs() }
      File(cache, "ggml-big.bin").sparse(40 * mib)
      File(cache, "ggml-small.bin").sparse(20 * mib)
      File(cache, "readme.txt").writeText("x")
      File(cache, "nested").mkdirs()
      File(cache, "nested/ggml-deep.bin").sparse(10 * mib)
      val candidates = WhisperModels.scan(listOf(cache, File(root, "missing")))
      assertEquals(setOf("ggml-big.bin", "ggml-small.bin", "readme.txt"), candidates.map { it.name }.toSet())
      assertEquals("ggml-small.bin", WhisperModels.find("ru") { candidates }?.name)
    }
    finally {
      root.deleteRecursively()
    }
  }

  private fun File.sparse(bytes: Long) = RandomAccessFile(this, "rw").use { it.setLength(bytes) }
}
