// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.watch.WatchTools
import java.io.File

/**
 * The engines this machine can use, and the choice among them
 *
 * Local recognition (sherpa-onnx inside the IDE) comes first: it needs no program on the machine, only its models,
 * which are downloaded once with the person's consent; until they are, the person is offered the download and
 * whatever the earlier engines would give, and no recording starts behind their back
 * whisper.cpp comes next and needs a model file: the person's own, or one found by [WhisperModels]
 * whisper (Python) is the other tool that answers to the name; it fetches a model of over a gigabyte on its first run,
 * and so it never runs unasked: only a yes that has been remembered lets it run on its own
 * A machine with none of them gets the problem that names what to install or to set
 * The button, the Telegram bridge and the doctor all ask here, so they cannot disagree about the machine
 */
object VoiceEngines {
  /**
   * What the settings say about voice, read at the click
   *
   * @property manualModel the field «whisper.cpp model» as typed; blank means none
   * @property language the language hint of the settings; blank means automatic
   * @property pythonConsent the person has agreed to whisper (Python) downloading its model
   * @property localEnabled local recognition may be used and offered
   * @property modelsPath the folder of the speech models as typed; blank means the system folder of the IDE
   * @property keepAliveSec how long the loaded models wait for the next dictation
   */
  data class Settings(
    val manualModel: String,
    val language: String,
    val pythonConsent: Boolean,
    val localEnabled: Boolean,
    val modelsPath: String,
    val keepAliveSec: Int,
  )

  /** What the machine and the settings leave to a dictation */
  sealed interface Choice {
    data class Use(val engine: VoiceEngine) : Choice

    /**
     * Only whisper (Python) can run, and the person has not agreed to it
     * [cliInstalled] tells whether a model for whisper.cpp would help
     */
    data class AskPython(val cliInstalled: Boolean) : Choice

    /**
     * Local recognition can run once [archives] are downloaded into [store]
     * [fallback] is what the earlier engines would give meanwhile; its [Use] is offered as the other button
     */
    data class OfferModels(
      val profile: SttCatalog.Profile,
      val archives: List<SttCatalog.Archive>,
      val store: SttModelStore,
      val fallback: Choice,
    ) : Choice {
      val bytes: Long get() = archives.sumOf { it.sizeBytes }
    }

    data class Unavailable(val problem: VoiceProblem) : Choice
  }

  /** The machine as the choice sees it, replaceable so the rules run without one */
  class Tools(
    val lookup: (String) -> String? = WatchTools::find,
    val hasFile: (String) -> Boolean = { File(it).isFile },
    val findModel: (String) -> WhisperModels.Candidate? = { WhisperModels.find(it) },
    val native: () -> SherpaNative.Status = SherpaNative::status,
    val catalog: () -> SttCatalog? = { SttCatalog.bundled },
    val store: (String) -> SttModelStore = { SttModelStore.of(it) },
  )

  fun choose(settings: Settings, tools: Tools = Tools()): Choice {
    val earlier = chooseEarlier(settings, tools)
    if (!settings.localEnabled) return earlier
    val catalog = tools.catalog() ?: return earlier
    val profile = catalog.profileFor(settings.language) ?: return earlier
    // A catalogue for another rate than the microphone's would feed the models audio they do not expect
    if (catalog.sampleRate != VoiceCapture.SAMPLE_RATE.toInt()) return earlier
    when (val native = tools.native()) {
      SherpaNative.Status.Ready -> Unit
      is SherpaNative.Status.Unsupported -> return earlier
      // Broken libraries are a defect to name, but only when nothing else can take the dictation
      is SherpaNative.Status.Failed ->
        return if (earlier is Choice.Unavailable) Choice.Unavailable(VoiceProblem.LocalEngineFailed(native.reason)) else earlier
    }
    val store = tools.store(settings.modelsPath)
    val missing = store.missing(profile)
    if (missing.isNotEmpty()) return Choice.OfferModels(profile, missing, store, earlier)
    val keepAliveMs = settings.keepAliveSec.toLong() * MS_PER_SECOND
    return Choice.Use(SherpaEngine(profile, store) { keepAliveMs })
  }

  private const val MS_PER_SECOND = 1000L

  /** whisper.cpp, then whisper (Python): the engines that need a program on the machine */
  private fun chooseEarlier(settings: Settings, tools: Tools): Choice {
    val cli = tools.lookup(VoiceTranscription.CPP_BINARY)
    if (cli != null) {
      when (val model = WhisperModels.resolve(settings.manualModel, settings.language, tools.hasFile, tools.findModel)) {
        is WhisperModels.Resolved.Manual -> return Choice.Use(cppEngine(cli, model.path, settings.language, tools))
        is WhisperModels.Resolved.Found -> return Choice.Use(cppEngine(cli, model.path, settings.language, tools))
        // A path the person typed is not replaced by a guess: the wrong one is named so it can be fixed
        is WhisperModels.Resolved.ManualMissing -> return Choice.Unavailable(VoiceProblem.ModelMissing(model.path))
        WhisperModels.Resolved.None -> Unit
      }
    }
    val python = tools.lookup(VoiceTranscription.OPENAI_BINARY)
    if (python != null) {
      return if (settings.pythonConsent) Choice.Use(PythonEngine(python, settings.language))
             else Choice.AskPython(cliInstalled = cli != null)
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
    override val live = server?.let { VoiceEngine.LivePreview.Snapshots(VoiceServer.PREVIEW_INTERVAL_MS, VoiceServer.PREVIEW_MAX_MS) }
    override val waitHint: String? = null

    override fun preview(wav: ByteArray): String =
      VoiceServer.getInstance().transcribe(wav, checkNotNull(server) { "no live server" }, VoiceServer.PREVIEW_TIMEOUT_MS)

    override fun listen(onText: (VoiceEngine.LiveText) -> Unit, onProblem: (VoiceProblem) -> Unit): VoiceEngine.Listening =
      error("snapshots, not frames")

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

    override fun listen(onText: (VoiceEngine.LiveText) -> Unit, onProblem: (VoiceProblem) -> Unit): VoiceEngine.Listening =
      error("no live text")

    override fun transcribe(audio: File, cancelled: () -> Boolean, note: (VoiceProblem) -> Unit): VoiceEngine.Outcome =
      oneShot(VoiceTranscription.Transcriber(binary, VoiceTranscription.Kind.OPENAI_WHISPER), audio, language, cancelled)
  }
}
