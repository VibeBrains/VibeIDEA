// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.i18n.VibeI18n.t
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One dictation from the click on the microphone to the text in the draft
 *
 * Everything that waits runs off the UI thread: choosing the engine, opening the device (the system may ask for permission
 * from inside the open call and hold it for as long as the person takes to answer), closing it, transcribing, downloading
 * Every way the dictation can stop short ends in a [VoiceProblem], and [Host.notice] puts it where the person looks
 * The state is read and written on the UI thread only; background work checks [generation] before it touches anything
 *
 * It knows nothing of the agent: no session, no turn, no mode.
 * The only thing it holds besides the microphone is the engine [VoiceEngines.choose] gave, and what it needs of the IDE
 * comes through [Host] (the panel) and [Env] (threads, the device, the choice, the download), so the whole flow runs in a test
 */
class VoiceDictation(private val host: Host, private val env: Env = IdeVoiceEnv) {
  /** The answer to the offer of whisper (Python) */
  enum class PythonAnswer {
    /** Use it, and remember that the person agreed */
    USE,

    /** The person went to set a whisper.cpp model instead; they start again when it is set */
    SETTINGS,
    CANCEL,
  }

  /** The answer to the offer of the models of local recognition */
  enum class ModelsAnswer {
    DOWNLOAD,

    /** Not now and not again: local recognition is turned off in the settings and the earlier engine takes this dictation */
    EARLIER,
    CANCEL,
  }

  /** What a background task with the IDE's progress bar gives the work it runs */
  interface Progress {
    /** How much is done, 0 to 1 */
    fun fraction(value: Double)

    /** The person pressed the cancel button of the bar */
    val cancelled: Boolean
  }

  /** The panel this dictation lives in; every callback is made on the UI thread */
  interface Host {
    val disposed: Boolean

    /** The whisper.cpp model file from the settings; empty when none is set */
    val modelPath: String

    /** The language hint from the settings; empty for automatic */
    val language: String

    /** The folder of the speech models from the settings; empty for the system folder of the IDE */
    val modelsPath: String

    /** How long the loaded models wait for the next dictation, in seconds */
    val keepAliveSec: Int

    /** The person's standing answer to whisper (Python) downloading its model; written when they say yes */
    var pythonConsent: Boolean

    /** Local recognition may be used and offered; written when the person declines it for good */
    var localEnabled: Boolean

    /** The button: tooltip and picture follow the state */
    fun face(state: VoiceFlow.State)

    /** The line under the input; null hides it */
    fun status(text: String?)
    fun notice(notice: VoiceNotice)

    /** The phrase being spoken, as the engine has it so far, into the input in place of its previous draft; never sent */
    fun draft(text: String)

    /** A phrase is final: it takes the place of its draft and stays */
    fun phrase(text: String)

    /**
     * The dictation is over and the input is the person's again
     * With [keep] the final phrases stay and an unfinished draft goes; without it everything the dictation wrote goes
     */
    fun close(keep: Boolean)

    /** The sound of a recording beginning or ending; the host plays it, or not, and never blocks */
    fun cue(cue: VoiceCue.Kind)

    /** The only tool on the machine is one that downloads over a gigabyte: asks the person, and returns when they have answered */
    fun askPython(offer: VoiceEngines.Choice.AskPython): PythonAnswer

    /** Local recognition needs its models: asks the person with their size, and returns when they have answered */
    fun askModels(offer: VoiceEngines.Choice.OfferModels): ModelsAnswer

    /** Runs [work] off the UI thread as a task with the IDE's progress bar and a cancel button; returns at once */
    fun withProgress(title: String, work: (Progress) -> Unit)
  }

  /** What a dictation takes from the IDE and the machine, replaceable so it can be driven without either */
  interface Env {
    /** Runs [task] off the UI thread */
    fun background(task: () -> Unit)

    /** Runs [task] on the UI thread */
    fun ui(task: () -> Unit)

    /** Runs [task] every [periodMs] off the UI thread until the returned function is called */
    fun every(periodMs: Long, task: () -> Unit): () -> Unit

    /** Runs [task] once on the UI thread after [delayMs], unless the returned function is called first */
    fun later(delayMs: Long, task: () -> Unit): () -> Unit

    /**
     * Opens the microphone, blocking while the system asks for permission; [onEnded] is told when the recording ends by itself
     * [onFrame], when given, hears every chunk the device delivers, on the thread that reads it
     */
    fun open(onEnded: (VoiceCapture.End) -> Unit, onFrame: ((ByteArray, Int) -> Unit)?): VoiceCapture.Recording

    fun choose(settings: VoiceEngines.Settings): VoiceEngines.Choice

    /** Downloads the models of [offer]; blocks, off the UI thread */
    fun install(offer: VoiceEngines.Choice.OfferModels, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): SttInstaller.Outcome
  }

  private var state = VoiceFlow.State.IDLE
  private var brokenReason: String? = null

  @Volatile private var recording: VoiceCapture.Recording? = null

  /** The engine of the dictation in progress; set when the recording starts */
  private var engine: VoiceEngine? = null

  /** The engine hearing the frames of the recording in progress, when it is one that does */
  private var listening: VoiceEngine.Listening? = null

  /** Ends the loop of previews; also called from the loop itself, which runs off the UI thread */
  @Volatile private var stopPreview: (() -> Unit)? = null
  private var stopDoneTimer: (() -> Unit)? = null

  /** A preview still waiting for the engine: the next beat is skipped, not queued behind it */
  private val previewBusy = AtomicBoolean(false)

  /** Changes whenever a dictation ends or is abandoned; work that finds it changed drops its result */
  @Volatile private var generation = 0L

  /** The live transcript already said its engine is down; the final one need not say it again */
  @Volatile private var liveFailureShown = false

  /** The offer of the last download, so that «retry» does not ask the same question twice */
  private var lastOffer: VoiceEngines.Choice.OfferModels? = null

  /** How loud the voice is now, 0 to 1 of full scale, for the pulse of the button; 0 when nothing records */
  val level: Double get() = recording?.level ?: 0.0

  /** True while something can still be dropped: the device opening, the recording, the text being made, a download; UI thread only */
  val active: Boolean get() = VoiceFlow.cancellable(state)

  /** Starts a dictation when none is going on, which is not what a click does in every state; false when one is */
  fun start(): Boolean {
    if (VoiceFlow.onClick(state) != VoiceFlow.Click.OPEN) return false
    begin()
    return true
  }

  /** What a click does now; UI thread only */
  fun toggle() {
    when (VoiceFlow.onClick(state)) {
      VoiceFlow.Click.OPEN -> begin()
      VoiceFlow.Click.CANCEL_OPEN -> abandon(null)
      VoiceFlow.Click.STOP -> stop()
      VoiceFlow.Click.NONE -> Unit
    }
  }

  /**
   * The person gives up (Esc): whatever is going on is dropped and nothing reaches the draft
   * Returns false when there was nothing to drop, so the key can do what it did before
   */
  fun cancel(): Boolean {
    if (!active) return false
    when (state) {
      VoiceFlow.State.RECORDING -> {
        stopPreviewLoop()
        recording?.let { discard(it) }
        recording = null
        listening?.cancel()
        listening = null
        host.cue(VoiceCue.Kind.STOP)
        abandon(VoiceProblem.RecordingCancelled)
      }
      VoiceFlow.State.TRANSCRIBING -> abandon(VoiceProblem.Cancelled)
      VoiceFlow.State.DOWNLOADING -> abandon(VoiceProblem.ModelsCancelled)
      else -> abandon(null)
    }
    return true
  }

  /** The download of the models again after it failed: the person agreed already, and is not asked twice; UI thread */
  fun downloadAgain() {
    if (VoiceFlow.onClick(state) != VoiceFlow.Click.OPEN) return
    lastOffer?.let { download(it) } ?: begin()
  }

  /** The panel is closing: the microphone must not stay on behind it */
  fun dispose() {
    generation++
    stopPreviewLoop()
    stopDoneTimer?.invoke()
    recording?.let { discard(it) }
    recording = null
    listening?.cancel()
    listening = null
    state = VoiceFlow.State.IDLE
  }

  private fun begin() {
    val mine = ++generation
    liveFailureShown = false
    brokenReason = null
    val settings = VoiceEngines.Settings(
      manualModel = host.modelPath,
      language = host.language,
      pythonConsent = host.pythonConsent,
      localEnabled = host.localEnabled,
      modelsPath = host.modelsPath,
      keepAliveSec = host.keepAliveSec,
    )
    setState(VoiceFlow.State.OPENING)
    pool(mine) {
      when (val choice = env.choose(settings)) {
        is VoiceEngines.Choice.Unavailable -> ui { backToIdle(mine) { host.notice(choice.problem.notice()) } }
        is VoiceEngines.Choice.AskPython -> ui { backToIdle(mine) { offer(choice) } }
        is VoiceEngines.Choice.OfferModels -> ui { backToIdle(mine) { offerModels(choice) } }
        is VoiceEngines.Choice.Use -> openWith(choice.engine, mine)
      }
    }
  }

  /** Whisper (Python) is the only tool: no recording starts until the person has chosen between it and a model for whisper.cpp */
  private fun offer(choice: VoiceEngines.Choice.AskPython) {
    if (host.askPython(choice) != PythonAnswer.USE) return
    host.pythonConsent = true
    begin()
  }

  /** Local recognition lacks its models: no recording starts until they are downloaded or the person chose otherwise */
  private fun offerModels(choice: VoiceEngines.Choice.OfferModels) {
    when (host.askModels(choice)) {
      ModelsAnswer.DOWNLOAD -> download(choice)
      ModelsAnswer.EARLIER -> {
        host.localEnabled = false
        begin()
      }
      ModelsAnswer.CANCEL -> Unit
    }
  }

  /**
   * The models are downloaded under the IDE's progress bar and the line under the input counts the percent
   * The button turns meanwhile and a click on it does nothing; the cancel of the bar and Esc both stop the download
   * When the models are in place the dictation does not start by itself: a microphone that opens minutes after the click
   * is a microphone the person did not expect; the line offers the recording with one click instead
   */
  private fun download(offer: VoiceEngines.Choice.OfferModels) {
    if (!downloading.compareAndSet(false, true)) {
      host.notice(VoiceProblem.ModelsBusy.notice())
      return
    }
    lastOffer = offer
    val mine = ++generation
    setState(VoiceFlow.State.DOWNLOADING)
    val title = t("chat.voice.downloadTitle", "mb" to megabytes(offer.bytes))
    host.withProgress(title) { progress ->
      var shown = -1
      val outcome = try {
        env.install(offer, progress = { done, total ->
          val fraction = if (total > 0) done.toDouble() / total else 0.0
          progress.fraction(fraction)
          val percent = (fraction * PERCENT).toInt()
          if (percent != shown) {
            shown = percent
            ui { if (live(mine) && state == VoiceFlow.State.DOWNLOADING) host.status(VoiceFlow.downloading(percent)) }
          }
        }, cancelled = { progress.cancelled || generation != mine })
      }
      catch (e: Exception) {
        SttInstaller.Outcome.Failed(SttInstaller.Stage.DISK, "", reasonOf(e))
      }
      finally {
        downloading.set(false)
      }
      ui { downloaded(mine, offer, outcome) }
    }
  }

  private fun downloaded(mine: Long, offer: VoiceEngines.Choice.OfferModels, outcome: SttInstaller.Outcome) {
    // Esc already said that the download stopped; a closed panel has nobody to tell
    if (!live(mine)) return
    setState(VoiceFlow.State.IDLE)
    val problem = when (outcome) {
      SttInstaller.Outcome.Done -> VoiceProblem.ModelsReady
      SttInstaller.Outcome.Cancelled -> VoiceProblem.ModelsCancelled
      is SttInstaller.Outcome.Failed -> VoiceProblem.ModelsFailed(outcome.stage, outcome.reason, offer.store.root.toString())
    }
    host.notice(problem.notice())
  }

  /** Off the UI thread: the device opens, and the result is taken on the UI thread unless the person has given up meanwhile */
  private fun openWith(chosen: VoiceEngine, mine: Long) {
    val heard = if (chosen.live != VoiceEngine.LivePreview.Frames) null
                else chosen.listen({ text -> liveText(mine, text) }) { problem ->
                  liveFailureShown = true
                  tell(mine, problem)
                }
    val opened = runCatching { env.open({ end -> ui { onEnded(mine, end) } }, heard?.let { l -> { pcm, length -> l.feed(pcm, length) } }) }
    ui {
      val taken = opened.getOrNull()
      when {
        // Cancelled while the system was still asking: the device opened for nobody and is closed again
        generation != mine || host.disposed -> {
          taken?.let { discard(it) }
          heard?.cancel()
        }
        taken == null -> {
          heard?.cancel()
          setState(VoiceFlow.State.IDLE)
          host.notice(VoiceProblem.NoMicrophone(opened.exceptionOrNull()?.let { reasonOf(it) }.orEmpty()).notice())
        }
        else -> {
          engine = chosen
          listening = heard
          recording = taken
          setState(VoiceFlow.State.RECORDING)
          // The sound says the device is really open: it is played now and not at the click
          host.cue(VoiceCue.Kind.START)
          startPreview(taken, mine, chosen)
        }
      }
    }
  }

  /** What an engine that hears the frames says, into the input while the dictation is still this one */
  private fun liveText(mine: Long, text: VoiceEngine.LiveText) = ui {
    if (!live(mine) || (state != VoiceFlow.State.RECORDING && state != VoiceFlow.State.TRANSCRIBING)) return@ui
    when (text) {
      is VoiceEngine.LiveText.Draft -> host.draft(text.text)
      is VoiceEngine.LiveText.Phrase -> host.phrase(text.text)
    }
  }

  /** The recording ended without a click: the cap, or the device went away */
  private fun onEnded(mine: Long, end: VoiceCapture.End) {
    if (generation != mine || state != VoiceFlow.State.RECORDING) return
    when (end) {
      VoiceCapture.End.Limit -> host.notice(VoiceProblem.limitReached().notice())
      is VoiceCapture.End.Device -> {
        brokenReason = end.reason
        host.notice(VoiceProblem.RecordingBroke(end.reason).notice())
      }
    }
    stop()
  }

  private fun stop() {
    val current = recording ?: return
    val used = engine ?: return
    val heard = listening
    recording = null
    listening = null
    stopPreviewLoop()
    host.cue(VoiceCue.Kind.STOP)
    val mine = generation
    val broken = brokenReason
    setState(VoiceFlow.State.TRANSCRIBING)
    pool(mine) {
      val captured = runCatching { current.stop() }.getOrElse { VoiceCapture.Captured.Failed(reasonOf(it)) }
      val problem = VoiceProblem.ofCapture(captured)
      when {
        captured is VoiceCapture.Captured.Audio && heard != null -> {
          // The engine heard every frame already; the file of the recording is not needed by it
          runCatching { captured.file.delete() }
          finish(heard, mine)
        }
        captured is VoiceCapture.Captured.Audio -> transcribe(captured.file, mine, used)
        // The person was told why the recording ended; "too short" on top of it would only repeat that
        problem == VoiceProblem.TooShort && broken != null -> {
          heard?.cancel()
          end(mine, null)
        }
        else -> {
          heard?.cancel()
          end(mine, problem)
        }
      }
    }
  }

  /** Leaves whatever is going on; the work in flight finds [generation] changed and drops its result */
  private fun abandon(problem: VoiceProblem?) {
    generation++
    host.close(keep = false)
    setState(VoiceFlow.State.IDLE)
    problem?.let { host.notice(it.notice()) }
  }

  /** The last phrase of an engine that heard the recording frame by frame; the others are in the input already */
  private fun finish(heard: VoiceEngine.Listening, mine: Long) {
    when (val outcome = heard.finish()) {
      VoiceEngine.Outcome.Cancelled -> Unit
      // A failure the engine reported while the person spoke was said then; the end is not a second line about it
      is VoiceEngine.Outcome.Failed -> end(mine, outcome.problem.takeUnless { liveFailureShown })
      is VoiceEngine.Outcome.Text -> if (outcome.text.isBlank()) end(mine, VoiceProblem.Empty) else done(mine, null)
    }
  }

  private fun transcribe(audio: File, mine: Long, used: VoiceEngine) {
    try {
      // The engine says what the wait is for when it is more than the default
      // A download can take minutes, and a bare spinner reads as a hang
      used.waitHint?.let { hint -> ui { if (live(mine)) host.status(VoiceFlow.waiting(hint)) } }
      val outcome = used.transcribe(audio, cancelled = { generation != mine }) { problem ->
        if (!liveFailureShown) tell(mine, problem)
      }
      when (outcome) {
        // A cancelled run has no problem: the key that cancelled it already ended the dictation
        VoiceEngine.Outcome.Cancelled -> Unit
        is VoiceEngine.Outcome.Failed -> end(mine, outcome.problem)
        is VoiceEngine.Outcome.Text -> {
          val task = VoiceTranscription.taskFrom(outcome.text)
          if (task != null) done(mine, task) else end(mine, VoiceProblem.ofTranscript(outcome.text))
        }
      }
    }
    finally {
      runCatching { audio.delete() }
    }
  }

  /**
   * Every few seconds the recording so far goes to the engine and its text shows in the input as the draft,
   * so the person sees that the microphone hears before they press stop and not after
   * Past the engine's own length the loop stops: each preview may transcribe the whole recording again
   * An engine that fails is named once and the loop ends, and the final transcript takes the other way
   */
  private fun startPreview(taken: VoiceCapture.Recording, mine: Long, chosen: VoiceEngine) {
    val live = chosen.live as? VoiceEngine.LivePreview.Snapshots ?: return
    stopPreview = env.every(live.intervalMs) {
      if (host.disposed || recording !== taken || !previewBusy.compareAndSet(false, true)) return@every
      try {
        if (taken.elapsedMs > live.maxRecordingMs) {
          ui { if (recording === taken) host.status(t("chat.voice.previewLimit")) }
          stopPreviewLoop()
          return@every
        }
        val wav = taken.snapshot() ?: return@every
        val text = chosen.preview(wav).lines().joinToString(" ") { it.trim() }.trim()
        if (text.isNotBlank()) ui { if (recording === taken) host.draft(text) }
      }
      catch (e: Exception) {
        stopPreviewLoop()
        liveFailureShown = true
        tell(mine, VoiceProblem.ServerFailed(reasonOf(e), afterStop = false))
      }
      finally {
        previewBusy.set(false)
      }
    }
  }

  private fun stopPreviewLoop() {
    stopPreview?.invoke()
    stopPreview = null
  }

  private fun setState(next: VoiceFlow.State) {
    stopDoneTimer?.invoke()
    stopDoneTimer = null
    state = next
    host.face(next)
    host.status(VoiceFlow.status(next))
  }

  private fun live(mine: Long): Boolean = generation == mine && !host.disposed

  /** Idle again, then [then]: for an answer that came back before the device was opened, if the person has not given up meanwhile */
  private fun backToIdle(mine: Long, then: () -> Unit) {
    if (!live(mine)) return
    setState(VoiceFlow.State.IDLE)
    then()
  }

  /** A line that does not end the dictation */
  private fun tell(mine: Long, problem: VoiceProblem) = ui { if (live(mine)) host.notice(problem.notice()) }

  /** The dictation is over without text in the draft, with the reason when there is one; final phrases already said stay */
  private fun end(mine: Long, problem: VoiceProblem?) = ui {
    if (!live(mine)) return@ui
    host.close(keep = true)
    setState(VoiceFlow.State.IDLE)
    problem?.let { host.notice(it.notice()) }
  }

  /**
   * The text is in the input: [final] is the text of an engine that answers after the recording, null when the phrases
   * of a live one are there already; the check mark shows for a moment
   */
  private fun done(mine: Long, final: String?) = ui {
    if (!live(mine)) return@ui
    final?.let { host.phrase(it) }
    host.close(keep = true)
    setState(VoiceFlow.State.DONE)
    stopDoneTimer = env.later(VoiceFlow.DONE_SHOW_MS) {
      if (state == VoiceFlow.State.DONE && generation == mine) setState(VoiceFlow.State.IDLE)
    }
  }

  /** The device is closed off the UI thread: closing a line can wait on the audio system */
  private fun discard(taken: VoiceCapture.Recording) = env.background { taken.cancel() }

  /** Background work of one dictation; a failure nobody foresaw still ends it with a line instead of a stuck button */
  private fun pool(mine: Long, task: () -> Unit) = env.background {
    try {
      task()
    }
    catch (e: Throwable) {
      end(mine, VoiceProblem.TranscriberNotStarted(reasonOf(e)))
    }
  }

  private fun ui(task: () -> Unit) = env.ui(task)

  companion object {
    private const val PERCENT = 100
    private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

    /** One download for the whole IDE: two panels must not fill the same folder at once */
    private val downloading = AtomicBoolean(false)

    /** [bytes] in whole megabytes, as the dialog and the progress bar say them */
    fun megabytes(bytes: Long): Long = Math.round(bytes / BYTES_PER_MEGABYTE)
  }
}
