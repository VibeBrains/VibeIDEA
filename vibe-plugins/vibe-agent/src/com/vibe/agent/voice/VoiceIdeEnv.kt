// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.vibe.agent.settings.VibeChatSettings
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** The real threads, the real microphone and the real machine behind a [VoiceDictation] */
object IdeVoiceEnv : VoiceDictation.Env {
  private const val TEMP_PREFIX = "vibe-voice-"
  private val log = logger<IdeVoiceEnv>()

  override fun background(task: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread(Runnable { task() })
  }

  override fun ui(task: () -> Unit) = SwingUtilities.invokeLater(task)

  override fun every(periodMs: Long, task: () -> Unit): () -> Unit {
    val scheduled = AppExecutorUtil.getAppScheduledExecutorService()
      .scheduleWithFixedDelay({ task() }, periodMs, periodMs, TimeUnit.MILLISECONDS)
    return { scheduled.cancel(false) }
  }

  override fun later(delayMs: Long, task: () -> Unit): () -> Unit {
    val scheduled = AppExecutorUtil.getAppScheduledExecutorService()
      .schedule({ SwingUtilities.invokeLater(task) }, delayMs, TimeUnit.MILLISECONDS)
    return { scheduled.cancel(false) }
  }

  override fun open(onEnded: (VoiceCapture.End) -> Unit): VoiceCapture.Recording {
    val target = File.createTempFile(TEMP_PREFIX, ".wav")
    try {
      return VoiceCapture.start(target, onEnded)
    }
    catch (e: Throwable) {
      target.delete()
      throw e
    }
  }

  override fun choose(model: String, language: String, pythonConsent: Boolean): VoiceEngines.Choice =
    VoiceEngines.choose(model, language, pythonConsent)

  /**
   * Plays the cue when the person has the recording sound on
   * Off the UI thread, and a device that cannot play it is a line in the log and not a failed dictation
   */
  fun playCue(kind: VoiceCue.Kind) {
    if (!VibeChatSettings.voiceSoundEnabled) return
    val volume = VibeChatSettings.voiceSoundVolume
    background { runCatching { VoiceCue.play(kind, volume) }.onFailure { log.warn("voice cue was not played: ${it.message}") } }
  }
}
