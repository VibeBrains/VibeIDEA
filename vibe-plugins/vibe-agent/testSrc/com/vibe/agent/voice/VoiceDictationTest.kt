// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.voice.VoiceDictation.ModelsAnswer
import com.vibe.agent.voice.VoiceDictation.PythonAnswer
import com.vibe.agent.voice.VoiceFlow.State
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A whole dictation, driven with a fake microphone, a fake engine and a fake panel
 *
 * None of it needs an agent: there is no session here, no turn and no client, and the dictation runs from the click to the draft
 */
class VoiceDictationTest {
  private class Host : VoiceDictation.Host {
    override var disposed = false
    override var modelPath = ""
    override var language = "ru"
    override var modelsPath = ""
    override var keepAliveSec = 300
    override var pythonConsent = false
    override var localEnabled = true
    var answer = PythonAnswer.CANCEL
    var modelsAnswer = ModelsAnswer.CANCEL
    val asked = mutableListOf<VoiceEngines.Choice.AskPython>()
    val offered = mutableListOf<VoiceEngines.Choice.OfferModels>()
    val faces = mutableListOf<State>()
    val statuses = mutableListOf<String?>()
    val notices = mutableListOf<VoiceNotice>()
    val drafts = mutableListOf<String>()

    /** The final phrases put into the input, in order: the text of the dictation */
    val delivered = mutableListOf<String>()
    val closes = mutableListOf<Boolean>()
    val cues = mutableListOf<VoiceCue.Kind>()
    val progressTitles = mutableListOf<String>()
    val fractions = mutableListOf<Double>()
    var barCancelled = false

    override fun face(state: State) { faces += state }
    override fun status(text: String?) { statuses += text }
    override fun notice(notice: VoiceNotice) { notices += notice }
    override fun draft(text: String) { drafts += text }
    override fun phrase(text: String) { delivered += text }
    override fun close(keep: Boolean) { closes += keep }
    override fun cue(cue: VoiceCue.Kind) { cues += cue }
    override fun askPython(offer: VoiceEngines.Choice.AskPython): PythonAnswer { asked += offer; return answer }
    override fun askModels(offer: VoiceEngines.Choice.OfferModels): ModelsAnswer { offered += offer; return modelsAnswer }

    override fun withProgress(title: String, work: (VoiceDictation.Progress) -> Unit) {
      progressTitles += title
      work(object : VoiceDictation.Progress {
        override fun fraction(value: Double) { fractions += value }
        override val cancelled: Boolean get() = barCancelled
      })
    }
  }

  private class Recording(val onEnded: (VoiceCapture.End) -> Unit, val onFrame: ((ByteArray, Int) -> Unit)?) : VoiceCapture.Recording {
    var cancelled = false
    var stopped = false
    override var elapsedMs = 1_000L
    override var level = 0.0
    var snapshotBytes: ByteArray? = ByteArray(10)
    val audio: File = File.createTempFile("vibe-voice-test-", ".wav")
    var result: VoiceCapture.Captured = VoiceCapture.Captured.Audio(audio)
    override fun snapshot(): ByteArray? = snapshotBytes
    override fun stop(): VoiceCapture.Captured { stopped = true; return result }
    override fun cancel() { cancelled = true }
  }

  private class Engine(override val live: VoiceEngine.LivePreview? = null, override val waitHint: String? = null) : VoiceEngine {
    override val name = "fake"
    override val model: String? = null
    var previewText = "привет мир"
    var previewFails: Exception? = null
    val previews = mutableListOf<ByteArray>()
    var work: (cancelled: () -> Boolean, note: (VoiceProblem) -> Unit) -> VoiceEngine.Outcome = { _, _ -> VoiceEngine.Outcome.Text(TASK) }

    /** What a frame engine says, and what its finish answers */
    var listened: Listening? = null
    var finishWith: (Listening) -> VoiceEngine.Outcome = { VoiceEngine.Outcome.Text(it.phrases.joinToString(" ")) }

    inner class Listening(val onText: (VoiceEngine.LiveText) -> Unit, val onProblem: (VoiceProblem) -> Unit) : VoiceEngine.Listening {
      val fed = mutableListOf<Int>()
      val phrases = mutableListOf<String>()
      var finished = false
      var cancelled = false
      override fun feed(pcm: ByteArray, length: Int) { fed += length }
      override fun finish(): VoiceEngine.Outcome { finished = true; return finishWith(this) }
      override fun cancel() { cancelled = true }

      fun say(text: VoiceEngine.LiveText) {
        if (text is VoiceEngine.LiveText.Phrase) phrases += text.text
        onText(text)
      }
    }

    override fun preview(wav: ByteArray): String { previews += wav; previewFails?.let { throw it }; return previewText }
    override fun listen(onText: (VoiceEngine.LiveText) -> Unit, onProblem: (VoiceProblem) -> Unit): VoiceEngine.Listening =
      Listening(onText, onProblem).also { listened = it }
    override fun transcribe(audio: File, cancelled: () -> Boolean, note: (VoiceProblem) -> Unit) = work(cancelled, note)
  }

  private class Env(val engine: Engine) : VoiceDictation.Env {
    /** Background work waits here when [deferred], so a test can stand between the click and the device opening */
    var deferred = false
    val queue = ArrayDeque<() -> Unit>()
    val every = mutableListOf<() -> Unit>()
    val later = mutableListOf<() -> Unit>()
    val opened = mutableListOf<Recording>()
    var openFails: Throwable? = null
    var choice: (VoiceEngines.Settings) -> VoiceEngines.Choice = { VoiceEngines.Choice.Use(engine) }
    val chosenWith = mutableListOf<VoiceEngines.Settings>()
    var installWith: (progress: (Long, Long) -> Unit, cancelled: () -> Boolean) -> SttInstaller.Outcome = { progress, _ ->
      progress(50, 100)
      progress(100, 100)
      SttInstaller.Outcome.Done
    }
    val installed = mutableListOf<VoiceEngines.Choice.OfferModels>()

    override fun background(task: () -> Unit) { if (deferred) queue.addLast(task) else task() }
    override fun ui(task: () -> Unit) = task()
    override fun every(periodMs: Long, task: () -> Unit): () -> Unit { every += task; return { every -= task } }
    override fun later(delayMs: Long, task: () -> Unit): () -> Unit { later += task; return { later -= task } }
    override fun open(onEnded: (VoiceCapture.End) -> Unit, onFrame: ((ByteArray, Int) -> Unit)?): VoiceCapture.Recording {
      openFails?.let { throw it }
      return Recording(onEnded, onFrame).also { opened += it }
    }

    override fun choose(settings: VoiceEngines.Settings): VoiceEngines.Choice {
      chosenWith += settings
      return choice(settings)
    }

    override fun install(
      offer: VoiceEngines.Choice.OfferModels,
      progress: (Long, Long) -> Unit,
      cancelled: () -> Boolean,
    ): SttInstaller.Outcome {
      installed += offer
      return installWith(progress, cancelled)
    }

    fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
  }

  private val host = Host()
  private val engine = Engine()
  private val env = Env(engine)
  private val dictation = VoiceDictation(host, env)

  private fun record() { dictation.toggle(); dictation.toggle() }

  @Test
  fun `a click records, a second one makes the text, and it lands in the draft under a check mark that fades`() {
    dictation.toggle()
    assertEquals(listOf(State.OPENING, State.RECORDING), host.faces)
    dictation.toggle()
    assertEquals(listOf(State.OPENING, State.RECORDING, State.TRANSCRIBING, State.DONE), host.faces)
    assertEquals(listOf(TASK), host.delivered)
    assertTrue(host.notices.isEmpty(), host.notices.toString())
    assertFalse(env.opened.single().audio.exists(), "the recording on disk is gone when its text is made")
    env.later.single()()
    assertEquals(State.IDLE, host.faces.last())
  }

  @Test
  fun `the click that follows a finished text records again and the check mark does not come back to undo it`() {
    record()
    dictation.toggle()
    assertEquals(State.RECORDING, host.faces.last())
    assertTrue(env.later.isEmpty(), "the timer of the check mark is cancelled by the next dictation")
  }

  @Test
  fun `the engine, the model, the language and the consent are read at the click`() {
    host.modelPath = "/m/ggml-small.bin"
    host.language = "en"
    host.pythonConsent = true
    host.localEnabled = false
    host.modelsPath = "/models"
    host.keepAliveSec = 7
    dictation.toggle()
    assertEquals(listOf(VoiceEngines.Settings("/m/ggml-small.bin", "en", true, false, "/models", 7)), env.chosenWith)
  }

  @Test
  fun `the start is played when the device is open, not at the click, and the stop when the recording stops`() {
    env.deferred = true
    dictation.toggle()
    assertEquals(State.OPENING, host.faces.last())
    assertTrue(host.cues.isEmpty(), "the microphone is not open yet, and a sound now would lie")
    env.drain()
    assertEquals(listOf(VoiceCue.Kind.START), host.cues)
    dictation.toggle()
    assertEquals(listOf(VoiceCue.Kind.START, VoiceCue.Kind.STOP), host.cues)
  }

  @Test
  fun `a device that will not open plays nothing and says so`() {
    env.openFails = IllegalStateException("line busy")
    dictation.toggle()
    assertEquals(State.IDLE, host.faces.last())
    assertTrue(host.cues.isEmpty())
    assertTrue("line busy" in host.notices.single().text)
  }

  @Test
  fun `a click while the text is made cancels nothing, and the text arrives`() {
    var cancelledAfterClick: Boolean? = null
    engine.work = { cancelled, _ ->
      dictation.toggle()
      dictation.toggle()
      cancelledAfterClick = cancelled()
      VoiceEngine.Outcome.Text(TASK)
    }
    record()
    assertEquals(false, cancelledAfterClick)
    assertEquals(listOf(TASK), host.delivered)
    assertEquals(1, env.opened.size, "the clicks did not start another recording either")
  }

  @Test
  fun `Esc while the text is made drops it, the engine is told, and nothing reaches the draft`() {
    var told = false
    engine.work = { cancelled, _ ->
      assertTrue(dictation.cancel())
      told = cancelled()
      VoiceEngine.Outcome.Cancelled
    }
    record()
    assertTrue(told, "the engine sees that the person gave up and stops what it started")
    assertTrue(host.delivered.isEmpty())
    assertEquals(State.IDLE, host.faces.last())
    assertFalse(State.DONE in host.faces)
    assertEquals(VoiceProblem.Cancelled.notice().text, host.notices.single().text)
  }

  @Test
  fun `Esc while recording closes the device, plays the stop, transcribes nothing and says so`() {
    dictation.toggle()
    val taken = env.opened.single()
    assertTrue(dictation.cancel())
    assertTrue(taken.cancelled)
    assertFalse(taken.stopped, "a dropped recording is not stopped into a transcription")
    assertEquals(listOf(VoiceCue.Kind.START, VoiceCue.Kind.STOP), host.cues)
    assertEquals(State.IDLE, host.faces.last())
    assertEquals(VoiceProblem.RecordingCancelled.notice().text, host.notices.single().text)
    assertTrue(host.delivered.isEmpty())
    assertTrue(env.every.isEmpty())
  }

  @Test
  fun `Esc while the device opens abandons it, and the device that opens afterwards is closed again`() {
    env.deferred = true
    dictation.toggle()
    assertTrue(dictation.cancel())
    assertEquals(State.IDLE, host.faces.last())
    env.drain()
    assertTrue(env.opened.single().cancelled, "a device opened for nobody is not left on")
    assertTrue(host.cues.isEmpty())
    assertFalse(State.RECORDING in host.faces)
  }

  @Test
  fun `a click while the device opens also abandons it, since nothing is lost yet`() {
    env.deferred = true
    dictation.toggle()
    dictation.toggle()
    assertEquals(State.IDLE, host.faces.last())
    env.drain()
    assertTrue(env.opened.single().cancelled)
  }

  @Test
  fun `Esc with nothing going on does nothing and says it did nothing, so the key keeps its other meaning`() {
    assertFalse(dictation.cancel())
    record()
    assertFalse(dictation.cancel(), "a finished dictation has nothing to drop")
    assertFalse(dictation.active)
  }

  @Test
  fun `the dictation is active exactly while it can be dropped`() {
    assertFalse(dictation.active)
    dictation.toggle()
    assertTrue(dictation.active)
    dictation.toggle()
    assertFalse(dictation.active, "the fake engine answers at once")
  }

  @Test
  fun `with only python whisper at hand and no yes the person is asked, and nothing is recorded until they answer`() {
    env.choice = { settings ->
      if (settings.pythonConsent) VoiceEngines.Choice.Use(engine) else VoiceEngines.Choice.AskPython(cliInstalled = true)
    }
    host.answer = PythonAnswer.CANCEL
    dictation.toggle()
    assertEquals(listOf(VoiceEngines.Choice.AskPython(true)), host.asked)
    assertTrue(env.opened.isEmpty(), "no microphone is opened for an engine nobody agreed to")
    assertEquals(State.IDLE, host.faces.last())
    assertFalse(host.pythonConsent)
  }

  @Test
  fun `a model chosen instead leaves the person at the settings, with nothing recorded and no consent given`() {
    env.choice = { VoiceEngines.Choice.AskPython(cliInstalled = true) }
    host.answer = PythonAnswer.SETTINGS
    dictation.toggle()
    assertTrue(env.opened.isEmpty())
    assertFalse(host.pythonConsent)
    assertTrue(host.notices.isEmpty(), "they chose; there is nothing to complain about")
  }

  @Test
  fun `a yes is remembered and the recording starts at once, and the next time nobody is asked`() {
    env.choice = { settings ->
      if (settings.pythonConsent) VoiceEngines.Choice.Use(engine) else VoiceEngines.Choice.AskPython(cliInstalled = false)
    }
    host.answer = PythonAnswer.USE
    dictation.toggle()
    assertTrue(host.pythonConsent)
    assertEquals(State.RECORDING, host.faces.last())
    dictation.toggle()
    dictation.toggle()
    assertEquals(1, host.asked.size, "the second recording asks nothing")
    assertEquals(State.RECORDING, host.faces.last())
  }

  @Test
  fun `a machine with no engine says what is missing, with the button that fixes it, and opens no microphone`() {
    env.choice = { VoiceEngines.Choice.Unavailable(VoiceProblem.NoModel) }
    dictation.toggle()
    assertTrue(env.opened.isEmpty())
    assertEquals(State.IDLE, host.faces.last())
    assertEquals(listOf(VoiceAction.CHOOSE_MODEL), host.notices.single().actions)
  }

  @Test
  fun `an engine with something to say about the wait says it under the input, with the way out`() {
    val slow = Engine(waitHint = "модель скачивается")
    val withHint = VoiceDictation(host, Env(slow).also { it.choice = { VoiceEngines.Choice.Use(slow) } })
    withHint.toggle()
    withHint.toggle()
    assertTrue(host.statuses.any { it != null && "модель скачивается" in it && "Esc" in it }, host.statuses.toString())
  }

  @Test
  fun `the live text of a snapshot engine goes into the input as a draft, and the final text takes its place`() {
    val live = Engine(live = VoiceEngine.LivePreview.Snapshots(2_500, 90_000))
    val e = Env(live).also { it.choice = { VoiceEngines.Choice.Use(live) } }
    val d = VoiceDictation(host, e)
    d.toggle()
    assertEquals(1, e.every.size, "the loop of previews runs while the recording does")
    e.every.single()()
    assertEquals(1, live.previews.size)
    assertEquals(listOf("привет мир"), host.drafts)
    d.toggle()
    assertTrue(e.every.isEmpty(), "the loop ends with the recording")
    assertEquals(listOf(TASK), host.delivered)
    assertEquals(listOf(true), host.closes)
  }

  @Test
  fun `an engine with no live text starts no loop`() {
    dictation.toggle()
    assertTrue(env.every.isEmpty())
  }

  @Test
  fun `past the length the engine allows the live text stops and says the rest comes after stop`() {
    val live = Engine(live = VoiceEngine.LivePreview.Snapshots(2_500, 90_000))
    val e = Env(live).also { it.choice = { VoiceEngines.Choice.Use(live) } }
    val d = VoiceDictation(host, e)
    d.toggle()
    e.opened.single().elapsedMs = 91_000
    e.every.single()()
    assertTrue(live.previews.isEmpty())
    assertTrue(e.every.isEmpty())
    assertTrue(host.statuses.last().orEmpty().contains("запись длинная"), host.statuses.last())
  }

  @Test
  fun `a live engine that fails is named once, the loop ends, and the final text does not repeat it`() {
    val live = Engine(live = VoiceEngine.LivePreview.Snapshots(2_500, 90_000))
    live.previewFails = IllegalStateException("exit 1")
    live.work = { _, note -> note(VoiceProblem.ServerFailed("exit 1", afterStop = true)); VoiceEngine.Outcome.Text(TASK) }
    val e = Env(live).also { it.choice = { VoiceEngines.Choice.Use(live) } }
    val d = VoiceDictation(host, e)
    d.toggle()
    e.every.single()()
    assertEquals(1, host.notices.size)
    assertTrue(e.every.isEmpty())
    d.toggle()
    assertEquals(1, host.notices.size, "already said")
    assertEquals(listOf(TASK), host.delivered, "the final text is made another way")
  }

  @Test
  fun `a live engine that was fine until the end and fails on the final text says so`() {
    engine.work = { _, note -> note(VoiceProblem.ServerFailed("exit 1", afterStop = true)); VoiceEngine.Outcome.Text(TASK) }
    record()
    assertEquals(1, host.notices.size)
    assertEquals(listOf(TASK), host.delivered)
  }

  @Test
  fun `an engine that fails ends the dictation with the reason, not with a stuck button`() {
    engine.work = { _, _ -> VoiceEngine.Outcome.Failed(VoiceProblem.TimedOut(180)) }
    record()
    assertEquals(State.IDLE, host.faces.last())
    assertTrue(host.delivered.isEmpty())
    assertEquals(listOf(VoiceAction.CHOOSE_MODEL, VoiceAction.RECORD_AGAIN), host.notices.single().actions)
  }

  @Test
  fun `an engine that throws still ends the dictation with a line`() {
    engine.work = { _, _ -> error("model file is corrupt") }
    record()
    assertEquals(State.IDLE, host.faces.last())
    assertTrue("model file is corrupt" in host.notices.single().text)
  }

  @Test
  fun `noise is not put in the draft, and the person is told what was heard`() {
    engine.work = { _, _ -> VoiceEngine.Outcome.Text("да") }
    record()
    assertTrue(host.delivered.isEmpty())
    assertTrue("«да»" in host.notices.single().text)
    assertFalse(State.DONE in host.faces)
  }

  @Test
  fun `a recording that is only silence says what to allow, with the buttons to do it`() {
    dictation.toggle()
    env.opened.single().result = VoiceCapture.Captured.Silence
    dictation.toggle()
    assertEquals(State.IDLE, host.faces.last())
    assertEquals(listOf(VoiceAction.MICROPHONE_SETTINGS, VoiceAction.RECORD_AGAIN), host.notices.single().actions)
  }

  @Test
  fun `a recording that broke is told once, and its remains are transcribed without a second complaint about the length`() {
    dictation.toggle()
    val taken = env.opened.single()
    taken.result = VoiceCapture.Captured.TooShort
    taken.onEnded(VoiceCapture.End.Device("device gone"))
    assertEquals(1, host.notices.size, host.notices.toString())
    assertTrue("device gone" in host.notices.single().text)
    assertEquals(State.IDLE, host.faces.last())
  }

  @Test
  fun `the cap ends the recording by itself and its text is still made`() {
    dictation.toggle()
    env.opened.single().onEnded(VoiceCapture.End.Limit)
    assertEquals(listOf(TASK), host.delivered)
    assertEquals(VoiceProblem.limitReached().notice().text, host.notices.single().text)
    assertEquals(listOf(VoiceCue.Kind.START, VoiceCue.Kind.STOP), host.cues)
  }

  @Test
  fun `closing the panel closes the microphone and drops what was going on`() {
    dictation.toggle()
    val taken = env.opened.single()
    dictation.dispose()
    assertTrue(taken.cancelled)
    assertTrue(env.every.isEmpty())
  }

  @Test
  fun `work that comes back after the panel closed touches nothing`() {
    env.deferred = true
    dictation.toggle()
    host.disposed = true
    env.drain()
    assertTrue(env.opened.single().cancelled)
    assertTrue(host.notices.isEmpty())
    assertFalse(State.RECORDING in host.faces)
  }

  @Test
  fun `a dictation starts only when none is going on`() {
    assertTrue(dictation.start())
    assertFalse(dictation.start(), "a button under an old line cannot stop the recording that is on")
    assertEquals(State.RECORDING, host.faces.last())
  }

  @Test
  fun `the level is that of the recording, and zero without one`() {
    assertEquals(0.0, dictation.level)
    dictation.toggle()
    env.opened.single().level = 0.42
    assertEquals(0.42, dictation.level)
    dictation.toggle()
    assertEquals(0.0, dictation.level)
  }

  @Test
  fun `a second dictation after a cancelled one works as the first`() {
    dictation.toggle()
    dictation.cancel()
    record()
    assertEquals(listOf(TASK), host.delivered)
    assertNotNull(env.opened.getOrNull(1))
    assertNull(host.notices.firstOrNull { it.text.contains("{") })
  }

  // --- an engine that hears the frames: local recognition ---

  private val frames = Engine(live = VoiceEngine.LivePreview.Frames)
  private val framesEnv = Env(frames).also { it.choice = { VoiceEngines.Choice.Use(frames) } }
  private val framesDictation = VoiceDictation(host, framesEnv)

  @Test
  fun `a frame engine hears every chunk of the device, drafts and phrases go into the input, and the stop keeps them`() {
    framesDictation.toggle()
    val taken = framesEnv.opened.single()
    val heard = assertNotNull(frames.listened)
    assertNotNull(taken.onFrame).invoke(ByteArray(4096), 4096)
    assertEquals(listOf(4096), heard.fed)
    heard.say(VoiceEngine.LiveText.Draft("привет"))
    heard.say(VoiceEngine.LiveText.Phrase("привет мир"))
    assertEquals(listOf("привет"), host.drafts)
    assertEquals(listOf("привет мир"), host.delivered)
    framesDictation.toggle()
    assertTrue(heard.finished)
    assertFalse(taken.audio.exists(), "the file of the recording is not kept for an engine that heard it already")
    assertEquals(listOf("привет мир"), host.delivered, "the phrases are in the input already and are not put there twice")
    assertEquals(listOf(true), host.closes)
    assertEquals(State.DONE, host.faces.last())
  }

  @Test
  fun `the last phrase said while the text is made still reaches the input, and nothing after the dictation does`() {
    frames.finishWith = { heard -> heard.say(VoiceEngine.LiveText.Phrase("хвост")); VoiceEngine.Outcome.Text("хвост") }
    framesDictation.toggle()
    framesDictation.toggle()
    assertEquals(listOf("хвост"), host.delivered)
    frames.listened!!.say(VoiceEngine.LiveText.Draft("поздно"))
    assertTrue(host.drafts.isEmpty(), "a draft after the end belongs to no dictation")
  }

  @Test
  fun `a frame engine that heard nothing says so, and one that broke says why`() {
    framesDictation.toggle()
    framesDictation.toggle()
    assertEquals(VoiceProblem.Empty.notice().text, host.notices.single().text)
    host.notices.clear()
    frames.finishWith = { VoiceEngine.Outcome.Failed(VoiceProblem.LocalEngineFailed("model broken")) }
    framesDictation.toggle()
    framesDictation.toggle()
    assertTrue("model broken" in host.notices.single().text)
    assertEquals(State.IDLE, host.faces.last())
  }

  @Test
  fun `a frame engine that breaks while the person speaks is said at once, and once`() {
    val broken = VoiceProblem.LocalEngineFailed("model did not load")
    frames.finishWith = { VoiceEngine.Outcome.Failed(broken) }
    framesDictation.toggle()
    assertNotNull(frames.listened).onProblem(broken)
    assertEquals(1, host.notices.size, "said while recording, so the person does not talk to a broken engine until the stop")
    framesDictation.toggle()
    assertEquals(1, host.notices.size, "the stop does not say it a second time")
    assertEquals(State.IDLE, host.faces.last())
  }

  @Test
  fun `Esc while a frame engine listens drops it and takes out all the dictation wrote`() {
    framesDictation.toggle()
    val heard = assertNotNull(frames.listened)
    heard.say(VoiceEngine.LiveText.Phrase("лишнее"))
    assertTrue(framesDictation.cancel())
    assertTrue(heard.cancelled)
    assertFalse(heard.finished)
    assertEquals(listOf(false), host.closes)
  }

  @Test
  fun `a device that will not open lets the engine go too`() {
    framesEnv.openFails = IllegalStateException("busy")
    framesDictation.toggle()
    assertTrue(assertNotNull(frames.listened).cancelled)
  }

  @Test
  fun `silence from the device is not given to the engine to finish`() {
    framesDictation.toggle()
    framesEnv.opened.single().result = VoiceCapture.Captured.Silence
    framesDictation.toggle()
    val heard = assertNotNull(frames.listened)
    assertTrue(heard.cancelled)
    assertFalse(heard.finished)
  }

  // --- the models of local recognition ---

  private val offer = VoiceEngines.Choice.OfferModels(
    profile = checkNotNull(SttCatalog.bundled?.profiles?.get("ru")),
    archives = checkNotNull(SttCatalog.bundled?.profiles?.get("ru")).archives,
    store = SttModelStore(java.nio.file.Path.of("/models")),
    fallback = VoiceEngines.Choice.Use(Engine()),
  )

  @Test
  fun `without the models nothing records, the person is asked, and a no is just a no`() {
    env.choice = { offer }
    host.modelsAnswer = ModelsAnswer.CANCEL
    dictation.toggle()
    assertEquals(listOf(offer), host.offered)
    assertTrue(env.opened.isEmpty())
    assertTrue(env.installed.isEmpty())
    assertEquals(State.IDLE, host.faces.last())
    assertTrue(host.localEnabled, "a cancel is not a decision for good")
  }

  @Test
  fun `a yes downloads under the progress bar, counts the percent under the input, and offers the recording with one click`() {
    env.choice = { offer }
    host.modelsAnswer = ModelsAnswer.DOWNLOAD
    dictation.toggle()
    assertEquals(listOf(offer), env.installed)
    assertTrue(State.DOWNLOADING in host.faces)
    assertEquals(listOf(0.5, 1.0), host.fractions)
    assertTrue(host.statuses.contains(VoiceFlow.downloading(50)), host.statuses.toString())
    assertTrue(host.progressTitles.single().contains("278"), host.progressTitles.toString())
    assertTrue(env.opened.isEmpty(), "the microphone does not open by itself minutes after the click")
    assertEquals(State.IDLE, host.faces.last())
    val ready = host.notices.single()
    assertEquals(VoiceProblem.ModelsReady.notice().text, ready.text)
    assertEquals(listOf(VoiceAction.RECORD_AGAIN), ready.actions)
  }

  @Test
  fun `a failed download says why, offers a retry that asks nothing again, and a click while downloading does nothing`() {
    env.choice = { offer }
    host.modelsAnswer = ModelsAnswer.DOWNLOAD
    env.installWith = { _, _ ->
      dictation.toggle()
      SttInstaller.Outcome.Failed(SttInstaller.Stage.CHECKSUM, "t-one-ru", "x")
    }
    dictation.toggle()
    assertEquals(1, env.chosenWith.size, "the click during the download started nothing")
    val failed = host.notices.single()
    assertEquals(listOf(VoiceAction.DOWNLOAD_AGAIN, VoiceAction.VOICE_SETTINGS), failed.actions)
    env.installWith = { _, _ -> SttInstaller.Outcome.Done }
    dictation.downloadAgain()
    assertEquals(1, host.offered.size, "the retry does not ask the person a second time")
    assertEquals(2, env.installed.size)
  }

  @Test
  fun `the cancel of the progress bar and Esc both stop the download`() {
    env.choice = { offer }
    host.modelsAnswer = ModelsAnswer.DOWNLOAD
    host.barCancelled = true
    env.installWith = { _, cancelled -> if (cancelled()) SttInstaller.Outcome.Cancelled else SttInstaller.Outcome.Done }
    dictation.toggle()
    assertEquals(VoiceProblem.ModelsCancelled.notice().text, host.notices.single().text)
    host.notices.clear()
    host.barCancelled = false
    var seen: Boolean? = null
    env.installWith = { _, cancelled ->
      assertTrue(dictation.cancel())
      seen = cancelled()
      SttInstaller.Outcome.Cancelled
    }
    dictation.toggle()
    assertEquals(true, seen)
    assertEquals(1, host.notices.size, "Esc said it once; the outcome of the download does not say it again")
  }

  @Test
  fun `the earlier engine instead turns local recognition off for good and records with it at once`() {
    env.choice = { settings -> if (settings.localEnabled) offer else VoiceEngines.Choice.Use(engine) }
    host.modelsAnswer = ModelsAnswer.EARLIER
    dictation.toggle()
    assertFalse(host.localEnabled)
    assertEquals(State.RECORDING, host.faces.last())
    assertTrue(env.installed.isEmpty())
  }

  private companion object {
    const val TASK = "запусти тесты проекта"
  }
}
