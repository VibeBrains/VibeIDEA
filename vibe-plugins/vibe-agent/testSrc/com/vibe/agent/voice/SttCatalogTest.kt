// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The catalogue of speech models in the shared set, and what its reader refuses to believe */
class SttCatalogTest {
  private val warnings = mutableListOf<String>()

  private fun parse(text: String) = SttCatalog.parse(text) { warnings += it }

  private val archive = """
    { "id": "a", "dir": "model-a", "url": "https://example.org/a.zip",
      "sha256": "${"ab".repeat(32)}", "sizeBytes": 10, "files": ["model.onnx", "tokens.txt"] }
  """.trimIndent()

  private fun catalog(archives: String = archive, profiles: String = """
    "ru": { "streaming": { "kind": "tone-ctc", "archive": "a", "model": "model.onnx", "tokens": "tokens.txt" } }
  """) = """{ "version": 1, "sampleRate": 16000, "archives": [ $archives ], "profiles": { $profiles } }"""

  @Test
  fun `the catalogue of the set reads whole, every archive is checkable, and both languages are there`() {
    val bundled = assertNotNull(SttCatalog.parse(assertNotNull(com.vibe.agent.defaults.VibeDefaults.setFile(SttCatalog.FILE))) { warnings += it })
    assertEquals(emptyList(), warnings)
    assertEquals(16_000, bundled.sampleRate)
    assertEquals(5, bundled.archives.size)
    bundled.archives.forEach { a ->
      assertTrue(a.url.startsWith("https://github.com/VibeBrains/VibeIDE/releases/download/stt-models-v1/"), a.url)
      assertEquals(64, a.sha256.length)
      assertTrue(a.sizeBytes > 0 && a.files.isNotEmpty(), a.id)
    }
    val ru = assertNotNull(bundled.profiles["ru"])
    assertEquals(SttCatalog.Kind.TONE_CTC, ru.streaming.kind)
    assertEquals(SttCatalog.Kind.NEMO_CTC, ru.final?.kind)
    assertEquals(291_387_000L, ru.downloadBytes, "T-one and GigaAM as the mirror holds them")
    val en = assertNotNull(bundled.profiles["en"])
    assertEquals(SttCatalog.Kind.TRANSDUCER, en.streaming.kind)
    assertNull(en.final)
  }

  @Test
  fun `the language of the settings picks the profile, and anything unknown falls back to English`() {
    val bundled = assertNotNull(SttCatalog.bundled)
    assertEquals("ru", bundled.profileFor("")?.id)
    assertEquals("ru", bundled.profileFor(" AUTO ")?.id)
    assertEquals("ru", bundled.profileFor("ru-RU")?.id)
    assertEquals("en", bundled.profileFor("en")?.id)
    assertEquals("en", bundled.profileFor("de")?.id)
  }

  @Test
  fun `an archive without a checksum, with a plain http address or a folder that climbs out is dropped`() {
    val noSha = archive.replace("\"sha256\": \"${"ab".repeat(32)}\",", "")
    val http = archive.replace("https://", "http://")
    val climbs = archive.replace("\"model-a\"", "\"../x\"")
    val shortSha = archive.replace("ab".repeat(32), "abc")
    for (broken in listOf(noSha, http, climbs, shortSha)) {
      warnings.clear()
      val parsed = assertNotNull(parse(catalog(broken)))
      assertTrue(parsed.archives.isEmpty(), broken)
      assertTrue(parsed.profiles.isEmpty(), "a profile of a dropped archive cannot be installed")
      assertTrue(warnings.isNotEmpty())
    }
  }

  @Test
  fun `a model that names a file its archive does not list is refused, so an installed archive is always a usable one`() {
    val parsed = assertNotNull(parse(catalog(profiles = """
      "ru": { "streaming": { "kind": "tone-ctc", "archive": "a", "model": "other.onnx", "tokens": "tokens.txt" } }
    """)))
    assertTrue(parsed.profiles.isEmpty())
    assertTrue(warnings.any { "other.onnx" in it }, warnings.toString())
  }

  @Test
  fun `a profile needs a streaming model, and its final model must decode a phrase rather than stream`() {
    val streamingAsFinal = """
      "ru": { "streaming": { "kind": "tone-ctc", "archive": "a", "model": "model.onnx", "tokens": "tokens.txt" },
              "final": { "kind": "tone-ctc", "archive": "a", "model": "model.onnx", "tokens": "tokens.txt" } }
    """
    assertTrue(assertNotNull(parse(catalog(profiles = streamingAsFinal))).profiles.isEmpty())
    val finalOnly = """
      "ru": { "streaming": { "kind": "nemo-ctc", "archive": "a", "model": "model.onnx", "tokens": "tokens.txt" } }
    """
    assertTrue(assertNotNull(parse(catalog(profiles = finalOnly))).profiles.isEmpty())
    val unknown = """ "ru": { "streaming": { "kind": "whisper", "archive": "a" } } """
    assertTrue(assertNotNull(parse(catalog(profiles = unknown))).profiles.isEmpty())
  }

  @Test
  fun `comments are allowed, as everywhere in the set, and text that is not a catalogue is null with a reason`() {
    assertNotNull(parse("// a note\n" + catalog()))
    assertNull(parse("not json"))
    assertTrue(warnings.isNotEmpty())
  }
}
