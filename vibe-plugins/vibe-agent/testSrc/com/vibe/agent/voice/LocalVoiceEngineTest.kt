// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.voice.VoiceEngines.Choice
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Local recognition in the choice of an engine: first when its libraries load and its models are in place,
 * offered with its size when they are not, and never in the way of an engine that works when it cannot run
 */
class LocalVoiceEngineTest {
  private val catalog = assertIsNotNull(SttCatalog.bundled)
  private val root: Path = Files.createTempDirectory("vibe-stt-test-")

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  private fun tools(
    native: SherpaNative.Status = SherpaNative.Status.Ready,
    cli: Boolean = true,
    model: Boolean = true,
    python: Boolean = false,
  ) = VoiceEngines.Tools(
    lookup = { name ->
      when (name) {
        "whisper-cli" -> "/bin/whisper-cli".takeIf { cli }
        "whisper" -> "/bin/whisper".takeIf { python }
        else -> null
      }
    },
    hasFile = { false },
    findModel = { if (model) WhisperModels.Candidate("/m/ggml-base.bin", 148_000_000) else null },
    native = { native },
    catalog = { catalog },
    store = { SttModelStore(root) },
  )

  private fun settings(language: String = "ru", local: Boolean = true, pythonConsent: Boolean = false) =
    VoiceEngines.Settings("", language, pythonConsent, local, modelsPath = "", keepAliveSec = 0)

  /** Lays out the files of [profile] the way an installation leaves them */
  private fun install(profile: String) {
    val store = SttModelStore(root)
    catalog.profiles.getValue(profile).archives.forEach { archive ->
      archive.files.forEach { file -> store.folderOf(archive).resolve(file).also { Files.createDirectories(it.parent); Files.writeString(it, "x") } }
    }
  }

  @Test
  fun `with the libraries loaded and the models in place local recognition is the engine, and it hears frames`() {
    install("ru")
    val engine = assertIs<Choice.Use>(VoiceEngines.choose(settings(), tools())).engine
    assertEquals("sherpa-onnx", engine.name)
    assertEquals(VoiceEngine.LivePreview.Frames, engine.live)
    assertEquals("t-one-ru + gigaam-v3-ctc-ru", engine.model)
  }

  @Test
  fun `without the models the person is offered exactly what is missing, with whatever would work meanwhile`() {
    val offer = assertIs<Choice.OfferModels>(VoiceEngines.choose(settings(), tools()))
    assertEquals(listOf("t-one-ru", "gigaam-v3-ctc-ru"), offer.archives.map { it.id })
    assertEquals(132_331_875L + 159_055_125L, offer.bytes)
    assertEquals("whisper.cpp", assertIs<Choice.Use>(offer.fallback).engine.name)
  }

  @Test
  fun `a half installed profile asks only for the archive that is not there`() {
    val store = SttModelStore(root)
    val tone = catalog.archives.first { it.id == "t-one-ru" }
    tone.files.forEach { store.folderOf(tone).resolve(it).also { f -> Files.createDirectories(f.parent); Files.writeString(f, "x") } }
    val offer = assertIs<Choice.OfferModels>(VoiceEngines.choose(settings(), tools()))
    assertEquals(listOf("gigaam-v3-ctc-ru"), offer.archives.map { it.id })
  }

  @Test
  fun `English gets its own model, and the automatic language is Russian`() {
    assertEquals(listOf("nemo-fc-transducer-en"), assertIs<Choice.OfferModels>(VoiceEngines.choose(settings("en"), tools())).archives.map { it.id })
    assertEquals(listOf("nemo-fc-transducer-en"), assertIs<Choice.OfferModels>(VoiceEngines.choose(settings("de"), tools())).archives.map { it.id })
    assertEquals("ru", assertIs<Choice.OfferModels>(VoiceEngines.choose(settings(""), tools())).profile.id)
    assertEquals("ru", assertIs<Choice.OfferModels>(VoiceEngines.choose(settings("auto"), tools())).profile.id)
  }

  @Test
  fun `turned off in the settings it is neither used nor offered`() {
    install("ru")
    assertEquals("whisper.cpp", assertIs<Choice.Use>(VoiceEngines.choose(settings(local = false), tools())).engine.name)
  }

  @Test
  fun `a platform without the libraries is not a problem to report, the earlier engines simply take the dictation`() {
    install("ru")
    val choice = VoiceEngines.choose(settings(), tools(native = SherpaNative.Status.Unsupported("linux-x64")))
    assertEquals("whisper.cpp", assertIs<Choice.Use>(choice).engine.name)
  }

  @Test
  fun `libraries that would not load are named only when nothing else can take the dictation`() {
    val broken = SherpaNative.Status.Failed("dlopen failed")
    assertEquals("whisper.cpp", assertIs<Choice.Use>(VoiceEngines.choose(settings(), tools(native = broken))).engine.name)
    val nothing = tools(native = broken, cli = false, model = false)
    assertEquals(Choice.Unavailable(VoiceProblem.LocalEngineFailed("dlopen failed")), VoiceEngines.choose(settings(), nothing))
  }

  @Test
  fun `local recognition never opens the way to python whisper without the consent`() {
    val booleans = listOf(false, true)
    for (installed in booleans) {
      if (installed) install("ru")
      for (local in booleans) for (cli in booleans) {
        val choice = VoiceEngines.choose(settings(local = local), tools(cli = cli, model = false, python = true))
        val ranPython = (choice as? Choice.Use)?.engine?.name == "whisper (Python)" ||
                        ((choice as? Choice.OfferModels)?.fallback as? Choice.Use)?.engine?.name == "whisper (Python)"
        assertTrue(!ranPython, "installed=$installed local=$local cli=$cli -> $choice")
      }
    }
  }

  private fun <T : Any> assertIsNotNull(value: T?): T = kotlin.test.assertNotNull(value, "the set has no voice/sttModels.jsonc that reads")
}
