// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.watch.WatchTools
import java.io.File

/**
 * The engines this machine can use, and the choice among them
 *
 * whisper.cpp comes first and needs a model file: the person's own, or one found by [WhisperModels]
 * whisper (Python) is the other tool that answers to the name; it fetches a model of over a gigabyte on its first run,
 * and so it never runs unasked: with no model for whisper.cpp the person is offered the choice, and only a yes
 * that has been remembered lets it run on its own
 * A machine with neither gets the problem that names what to install or to set
 */
object VoiceEngines {
  /** What the machine and the settings leave to a dictation */
  sealed interface Choice {
    data class Use(val engine: VoiceEngine) : Choice

    /**
     * Only whisper (Python) can run, and the person has not agreed to it
     * [cliInstalled] tells whether a model for whisper.cpp would help
     */
    data class AskPython(val cliInstalled: Boolean) : Choice
    data class Unavailable(val problem: VoiceProblem) : Choice
  }

  /** The machine as the choice sees it, replaceable so the rules run without one */
  class Tools(
    val lookup: (String) -> String? = WatchTools::find,
    val hasFile: (String) -> Boolean = { File(it).isFile },
    val findModel: (String) -> WhisperModels.Candidate? = { WhisperModels.find(it) },
  )

  /**
   * @param manualModel the field «whisper.cpp model» as typed; blank means none
   * @param language the language hint of the settings; blank means detect
   * @param pythonConsent the person has agreed to whisper (Python) downloading its model
   */
  fun choose(manualModel: String, language: String, pythonConsent: Boolean, tools: Tools = Tools()): Choice {
    val cli = tools.lookup(VoiceTranscription.CPP_BINARY)
    if (cli != null) {
      when (val model = WhisperModels.resolve(manualModel, language, tools.hasFile, tools.findModel)) {
        is WhisperModels.Resolved.Manual -> return Choice.Use(cppEngine(cli, model.path, language, tools))
        is WhisperModels.Resolved.Found -> return Choice.Use(cppEngine(cli, model.path, language, tools))
        // A path the person typed is not replaced by a guess: the wrong one is named so it can be fixed
        is WhisperModels.Resolved.ManualMissing -> return Choice.Unavailable(VoiceProblem.ModelMissing(model.path))
        WhisperModels.Resolved.None -> Unit
      }
    }
    val python = tools.lookup(VoiceTranscription.OPENAI_BINARY)
    if (python != null) {
      return if (pythonConsent) Choice.Use(PythonEngine(python, language)) else Choice.AskPython(cliInstalled = cli != null)
    }
    return Choice.Unavailable(if (cli != null) VoiceProblem.NoModel else VoiceProblem.NoTranscriber)
  }

  /** The model is known to exist by now, so the server's own check of the file is not asked a second time */
  private fun cppEngine(cli: String, model: String, language: String, tools: Tools): VoiceEngine =
    CppEngine(cli, model, language, VoiceServer.configOf(model, language, tools.lookup) { true })

  /** One-shot run of a transcriber over the finished recording, in the shape the engines answer in */
  private fun oneShot(
    transcriber: VoiceTranscription.Transcriber,
    audio: File,
    language: String,
    cancelled: () -> Boolean,
  ): VoiceEngine.Outcome {
    val once = VoiceTranscription.runOnce(transcriber, audio, audio.parentFile, language, cancelled = cancelled)
    return when {
      once is VoiceTranscription.Once.Text -> VoiceEngine.Outcome.Text(once.text)
      once === VoiceTranscription.Once.Cancelled -> VoiceEngine.Outcome.Cancelled
      else -> VoiceProblem.ofRun(once)?.let { VoiceEngine.Outcome.Failed(it) } ?: VoiceEngine.Outcome.Cancelled
    }
  }

  /**
   * whisper.cpp: with `whisper-server` beside it the model stays loaded and the text appears while the person speaks;
   * without it `whisper-cli` loads the model once per recording
   */
  private class CppEngine(
    private val cli: String,
    override val model: String,
    private val language: String,
    private val server: VoiceServer.Config?,
  ) : VoiceEngine {
    override val name = "whisper.cpp"
    override val live = server?.let { VoiceEngine.LivePreview(VoiceServer.PREVIEW_INTERVAL_MS, VoiceServer.PREVIEW_MAX_MS) }
    override val waitHint: String? = null

    override fun preview(wav: ByteArray): String =
      VoiceServer.getInstance().transcribe(wav, checkNotNull(server) { "no live server" }, VoiceServer.PREVIEW_TIMEOUT_MS)

    override fun transcribe(audio: File, cancelled: () -> Boolean, note: (VoiceProblem) -> Unit): VoiceEngine.Outcome {
      if (server != null) {
        // The resident server already holds the model the live text used; when it fails the one-shot run is the way on
        try {
          return VoiceEngine.Outcome.Text(VoiceServer.getInstance().transcribe(audio.readBytes(), server, VoiceServer.FINAL_TIMEOUT_MS))
        }
        catch (e: Exception) {
          note(VoiceProblem.ServerFailed(reasonOf(e), afterStop = true))
        }
      }
      return oneShot(VoiceTranscription.Transcriber(cli, VoiceTranscription.Kind.WHISPER_CPP, model), audio, language, cancelled)
    }
  }

  /** whisper (Python): the text only after the recording, and the first run downloads the model */
  private class PythonEngine(private val binary: String, private val language: String) : VoiceEngine {
    override val name = "whisper (Python)"
    override val model: String? = null
    override val live: VoiceEngine.LivePreview? = null
    override val waitHint: String get() = t("chat.voice.transcribingPython")

    override fun preview(wav: ByteArray): String = error("no live text")

    override fun transcribe(audio: File, cancelled: () -> Boolean, note: (VoiceProblem) -> Unit): VoiceEngine.Outcome =
      oneShot(VoiceTranscription.Transcriber(binary, VoiceTranscription.Kind.OPENAI_WHISPER), audio, language, cancelled)
  }
}
