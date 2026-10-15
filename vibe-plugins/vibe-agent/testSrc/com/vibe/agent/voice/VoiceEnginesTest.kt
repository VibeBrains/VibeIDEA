// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.voice.VoiceEngines.Choice
import com.vibe.agent.voice.WhisperModels.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which engine a machine gets, and that whisper (Python) never gets to run, and so to download, without a yes. */
class VoiceEnginesTest {
  private val cli = "/opt/homebrew/bin/whisper-cli"
  private val server = "/opt/homebrew/bin/whisper-server"
  private val python = "/opt/homebrew/bin/whisper"
  private val found = Candidate("/home/x/.cache/whisper.cpp/ggml-base-q5_1.bin", 59_707_625)

  private fun tools(
    cli: Boolean = true,
    server: Boolean = true,
    python: Boolean = false,
    found: Candidate? = this.found,
    existing: Set<String> = emptySet(),
    onFind: (String) -> Unit = {},
  ) = VoiceEngines.Tools(
    lookup = { name ->
      when (name) {
        "whisper-cli" -> this.cli.takeIf { cli }
        "whisper-server" -> this.server.takeIf { server }
        "whisper" -> this.python.takeIf { python }
        else -> null
      }
    },
    hasFile = { it in existing },
    findModel = { language -> onFind(language); found },
  )

  private fun engineOf(choice: Choice): VoiceEngine = assertIs<Choice.Use>(choice).engine

  @Test
  fun `a machine with whisper-cpp and a model on it gets a live engine without anything being set`() {
    val engine = engineOf(VoiceEngines.choose("", "ru", pythonConsent = false, tools()))
    assertEquals("whisper.cpp", engine.name)
    assertEquals(found.path, engine.model)
    assertNotNull(engine.live, "whisper-server is there, so the text appears while the person speaks")
    assertNull(engine.waitHint)
  }

  @Test
  fun `without whisper-server the same engine answers after the recording`() {
    val engine = engineOf(VoiceEngines.choose("", "ru", false, tools(server = false)))
    assertEquals("whisper.cpp", engine.name)
    assertNull(engine.live)
  }

  @Test
  fun `the path the person typed is used, not the found model`() {
    val engine = engineOf(VoiceEngines.choose("/mine/ggml-small.bin", "ru", false, tools(existing = setOf("/mine/ggml-small.bin"))))
    assertEquals("/mine/ggml-small.bin", engine.model)
  }

  @Test
  fun `a wrong typed path is named and not replaced by a guess, even with python whisper at hand and agreed to`() {
    val choice = VoiceEngines.choose("/mine/typo.bin", "ru", pythonConsent = true, tools(python = true))
    assertEquals(Choice.Unavailable(VoiceProblem.ModelMissing("/mine/typo.bin")), choice)
  }

  @Test
  fun `the language of the settings reaches the search for a model`() {
    var asked: String? = null
    VoiceEngines.choose("", "en", false, tools(onFind = { asked = it }))
    assertEquals("en", asked)
  }

  @Test
  fun `with whisper-cpp but no model, and python whisper there, the person is asked and nothing runs`() {
    val choice = VoiceEngines.choose("", "ru", pythonConsent = false, tools(found = null, python = true))
    assertEquals(Choice.AskPython(cliInstalled = true), choice)
  }

  @Test
  fun `with only python whisper the person is asked too, and told that a model for whisper-cpp would need the program`() {
    val pythonOnly = tools(cli = false, server = false, python = true)
    assertEquals(Choice.AskPython(cliInstalled = false), VoiceEngines.choose("", "ru", false, pythonOnly))
    // A model on disk does not help a program that is not installed
    val withModel = tools(cli = false, server = false, python = true, existing = setOf("/mine/ggml-small.bin"))
    assertEquals(Choice.AskPython(cliInstalled = false), VoiceEngines.choose("/mine/ggml-small.bin", "ru", false, withModel))
  }

  @Test
  fun `once the person has agreed, python whisper runs on its own and says what the wait is`() {
    val engine = engineOf(VoiceEngines.choose("", "ru", pythonConsent = true, tools(found = null, python = true)))
    assertEquals("whisper (Python)", engine.name)
    assertNull(engine.model)
    assertNull(engine.live)
    assertTrue(engine.waitHint.orEmpty().contains("скачивает"), engine.waitHint)
  }

  @Test
  fun `a model for whisper-cpp always beats python whisper, agreed to or not`() {
    val engine = engineOf(VoiceEngines.choose("", "ru", pythonConsent = true, tools(python = true)))
    assertEquals("whisper.cpp", engine.name)
  }

  @Test
  fun `whisper-cpp without a model and nothing else is a setting to fix, an empty machine is an install`() {
    assertEquals(Choice.Unavailable(VoiceProblem.NoModel), VoiceEngines.choose("", "ru", false, tools(found = null)))
    val empty = tools(cli = false, server = false, found = null)
    assertEquals(Choice.Unavailable(VoiceProblem.NoTranscriber), VoiceEngines.choose("", "ru", false, empty))
  }

  @Test
  fun `no combination of the machine and the settings starts python whisper without the consent`() {
    val booleans = listOf(false, true)
    for (hasCli in booleans) for (hasServer in booleans) for (hasPython in booleans) for (hasModel in booleans) {
      val choice = VoiceEngines.choose("", "ru", pythonConsent = false, tools(hasCli, hasServer, hasPython, found.takeIf { hasModel }))
      val ranPython = choice is Choice.Use && choice.engine.name == "whisper (Python)"
      assertEquals(false, ranPython, "cli=$hasCli server=$hasServer python=$hasPython model=$hasModel -> $choice")
    }
  }
}
