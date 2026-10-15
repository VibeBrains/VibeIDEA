// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.vibe.agent.i18n.VibeI18n.t
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * One dictation from the click on the microphone to the text in the draft
 *
 * Everything that waits runs off the UI thread: opening the device (the system may ask for permission from
 * inside the open call and hold it for as long as the person takes to answer), closing it, and transcribing
 * Every way the dictation can stop short ends in a [VoiceProblem], and [Host.notice] puts it where the person looks
 * The state is read and written on the UI thread only; background work checks [generation] before it touches anything
 */
class VoiceDictation(private val host: Host) {
  /** The panel this dictation lives in; every callback is made on the UI thread */
  interface Host {
    val disposed: Boolean

    /** The whisper.cpp model file from the settings; empty when none is set */
    val modelPath: String

    /** The language hint from the settings; empty for automatic */
    val language: String

    /** The button: tooltip and icon follow the state */
    fun face(state: VoiceFlow.State)

    /** The line under the input; null hides it */
    fun status(text: String?)
    fun notice(notice: VoiceNotice)

    /** The transcript, into the draft and not sent: what to do with the words stays the person's decision */
    fun deliver(text: String)
  }

  private var state = VoiceFlow.State.IDLE
  private var brokenReason: String? = null

  @Volatile private var recording: VoiceCapture.Recording? = null
  @Volatile private var previewLoop: ScheduledFuture<*>? = null

  /** A preview still waiting for the server: the next beat is skipped, not queued behind it */
  private val previewBusy = AtomicBoolean(false)

  /** Changes whenever a dictation ends or is abandoned; work that finds it changed drops its result */
  @Volatile private var generation = 0L

  /** The live transcript already said the server is down; the final one need not say it again */
  @Volatile private var serverFailureShown = false

  /** What a click does now; UI thread only */
  fun toggle() {
    when (VoiceFlow.onClick(state)) {
      VoiceFlow.Click.OPEN -> open()
      VoiceFlow.Click.CANCEL_OPEN -> abandon(null)
      VoiceFlow.Click.STOP -> stop()
      VoiceFlow.Click.CANCEL_TRANSCRIBE -> abandon(VoiceProblem.Cancelled)
    }
  }

  /** The panel is closing: the microphone must not stay on behind it */
  fun dispose() {
    generation++
    stopPreview()
    recording?.let { discard(it) }
    recording = null
    state = VoiceFlow.State.IDLE
  }

  private fun open() {
    val mine = ++generation
    serverFailureShown = false
    brokenReason = null
    setState(VoiceFlow.State.OPENING)
    pool(mine) {
      val opened = runCatching { openRecording(mine) }
      edt {
        val taken = opened.getOrNull()
        when {
          // Cancelled while the system was still asking: the device opened for nobody and is closed again
          generation != mine || host.disposed -> taken?.let { discard(it) }
          taken == null -> {
            setState(VoiceFlow.State.IDLE)
            host.notice(VoiceProblem.NoMicrophone(opened.exceptionOrNull()?.let { reasonOf(it) }.orEmpty()).notice())
          }
          else -> {
            recording = taken
            setState(VoiceFlow.State.RECORDING)
            startPreview(taken, mine)
          }
        }
      }
    }
  }

  private fun openRecording(mine: Long): VoiceCapture.Recording {
    val target = File.createTempFile(TEMP_PREFIX, ".wav")
    try {
      return VoiceCapture.start(target) { end -> edt { onEnded(mine, end) } }
    }
    catch (e: Throwable) {
      target.delete()
      throw e
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
    val active = recording ?: return
    recording = null
    stopPreview()
    val mine = generation
    val broken = brokenReason
    setState(VoiceFlow.State.TRANSCRIBING)
    pool(mine) {
      val captured = runCatching { active.stop() }.getOrElse { VoiceCapture.Captured.Failed(reasonOf(it)) }
      val problem = VoiceProblem.ofCapture(captured)
      when {
        captured is VoiceCapture.Captured.Audio -> transcribe(captured.file, mine)
        // The person was told why the recording ended; "too short" on top of it would only repeat that
        problem == VoiceProblem.TooShort && broken != null -> end(mine, null)
        else -> end(mine, problem)
      }
    }
  }

  /** Leaves whatever is going on; the work in flight finds [generation] changed and drops its result */
  private fun abandon(problem: VoiceProblem?) {
    generation++
    setState(VoiceFlow.State.IDLE)
    problem?.let { host.notice(it.notice()) }
  }

  private fun transcribe(audio: File, mine: Long) {
    try {
      val cancelled = { generation != mine }
      val model = host.modelPath
      val language = host.language
      val text = viaServer(audio, model, language, mine) ?: viaProgram(audio, model, language, mine, cancelled) ?: return
      if (cancelled()) return
      val problem = VoiceProblem.ofTranscript(text)
      end(mine, problem, if (problem == null) VoiceTranscription.taskFrom(text) else null)
    }
    finally {
      runCatching { audio.delete() }
    }
  }

  /** The resident server first: it already holds the model the live transcript used; null when it is not set up or failed */
  private fun viaServer(audio: File, model: String, language: String, mine: Long): String? {
    val config = VoiceServer.configOf(model, language) ?: return null
    return try {
      VoiceServer.getInstance().transcribe(audio.readBytes(), config, VoiceServer.FINAL_TIMEOUT_MS)
    }
    catch (e: Exception) {
      if (!serverFailureShown) tell(mine, VoiceProblem.ServerFailed(reasonOf(e), afterStop = true))
      null
    }
  }

  /** The one-shot transcriber on the machine; null after the dictation has been ended with the reason */
  private fun viaProgram(audio: File, model: String, language: String, mine: Long, cancelled: () -> Boolean): String? {
    val transcriber = VoiceTranscription.find(model, wav = true)
    if (transcriber == null) {
      end(mine, VoiceProblem.missingTranscriber(model))
      return null
    }
    // openai-whisper fetches its model on the first run; saying so is what keeps the wait from looking like a hang
    if (transcriber.kind == VoiceTranscription.Kind.OPENAI_WHISPER) {
      edt { if (live(mine)) host.status(VoiceFlow.line(t("chat.voice.transcribingPython"))) }
    }
    val once = VoiceTranscription.runOnce(transcriber, audio, audio.parentFile, language, cancelled = cancelled)
    if (once is VoiceTranscription.Once.Text) return once.text
    // A cancelled run has no problem: the click that cancelled it already ended the dictation
    VoiceProblem.ofRun(once)?.let { end(mine, it) }
    return null
  }

  /**
   * Every few seconds the recording so far goes to the resident server and its text shows under the input,
   * so the person sees that the microphone hears before they press stop and not after
   * Past [VoiceServer.PREVIEW_MAX_MS] the loop stops: each preview transcribes the whole recording again
   * A server that cannot start is named once and the loop ends, and the final transcript takes the other way
   */
  private fun startPreview(active: VoiceCapture.Recording, mine: Long) {
    val config = VoiceServer.configOf(host.modelPath, host.language) ?: return
    previewLoop = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
      if (host.disposed || recording !== active || !previewBusy.compareAndSet(false, true)) return@scheduleWithFixedDelay
      try {
        if (active.elapsedMs > VoiceServer.PREVIEW_MAX_MS) {
          edt { if (recording === active) host.status(t("chat.voice.previewLimit")) }
          previewLoop?.cancel(false)
          return@scheduleWithFixedDelay
        }
        val wav = active.snapshot() ?: return@scheduleWithFixedDelay
        val text = VoiceServer.getInstance().transcribe(wav, config, VoiceServer.PREVIEW_TIMEOUT_MS)
        if (text.isNotBlank()) edt { if (recording === active) host.status(VoiceFlow.line(text)) }
      }
      catch (e: Exception) {
        previewLoop?.cancel(false)
        serverFailureShown = true
        tell(mine, VoiceProblem.ServerFailed(reasonOf(e), afterStop = false))
      }
      finally {
        previewBusy.set(false)
      }
    }, VoiceServer.PREVIEW_INTERVAL_MS, VoiceServer.PREVIEW_INTERVAL_MS, TimeUnit.MILLISECONDS)
  }

  private fun stopPreview() {
    previewLoop?.cancel(false)
    previewLoop = null
  }

  private fun setState(next: VoiceFlow.State) {
    state = next
    host.face(next)
    host.status(VoiceFlow.status(next))
  }

  private fun live(mine: Long): Boolean = generation == mine && !host.disposed

  /** A line that does not end the dictation */
  private fun tell(mine: Long, problem: VoiceProblem) = edt { if (live(mine)) host.notice(problem.notice()) }

  /** The dictation is over: back to idle, with the reason it ended without text, or with the task for the draft */
  private fun end(mine: Long, problem: VoiceProblem?, task: String? = null) = edt {
    if (!live(mine)) return@edt
    setState(VoiceFlow.State.IDLE)
    problem?.let { host.notice(it.notice()) }
    task?.let { host.deliver(it) }
  }

  /** The device is closed off the UI thread: closing a line can wait on the audio system */
  private fun discard(taken: VoiceCapture.Recording) {
    ApplicationManager.getApplication().executeOnPooledThread(Runnable { taken.cancel() })
  }

  /** Background work of one dictation; a failure nobody foresaw still ends it with a line instead of a stuck button */
  private fun pool(mine: Long, task: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread(Runnable {
      try {
        task()
      }
      catch (e: Throwable) {
        end(mine, VoiceProblem.TranscriberNotStarted(reasonOf(e)))
      }
    })
  }

  private fun edt(task: () -> Unit) = SwingUtilities.invokeLater(task)

  private companion object {
    const val TEMP_PREFIX = "vibe-voice-"
  }
}
