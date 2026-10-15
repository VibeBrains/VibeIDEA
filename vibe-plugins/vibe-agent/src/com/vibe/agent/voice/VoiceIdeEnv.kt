// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.settings.VibeChatSettings
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** The real threads, the real microphone and the real machine behind a [VoiceDictation] */
object IdeVoiceEnv : VoiceDictation.Env {
  private const val TEMP_PREFIX = "vibe-voice-"
  private const val CONNECT_TIMEOUT_MS = 20_000
  private const val READ_TIMEOUT_MS = 60_000
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

  override fun open(onEnded: (VoiceCapture.End) -> Unit, onFrame: ((ByteArray, Int) -> Unit)?): VoiceCapture.Recording {
    val target = File.createTempFile(TEMP_PREFIX, ".wav")
    try {
      return if (onFrame != null) VoiceCapture.start(target, onEnded, onFrame) else VoiceCapture.start(target, onEnded)
    }
    catch (e: Throwable) {
      target.delete()
      throw e
    }
  }

  override fun choose(settings: VoiceEngines.Settings): VoiceEngines.Choice = VoiceEngines.choose(settings)

  override fun install(
    offer: VoiceEngines.Choice.OfferModels,
    progress: (Long, Long) -> Unit,
    cancelled: () -> Boolean,
  ): SttInstaller.Outcome = SttInstaller.install(offer.store, offer.archives, fetch, progress, cancelled)

  /**
   * The platform's own HTTP client: it follows the redirect of a release asset to its storage host,
   * and it goes through the proxy the person set for the IDE, which a bare JDK client would not know of
   */
  private val fetch = SttInstaller.Fetch { url, consume ->
    HttpRequests.request(url).productNameAsUserAgent().connectTimeout(CONNECT_TIMEOUT_MS).readTimeout(READ_TIMEOUT_MS)
      .connect { request -> consume(request.inputStream) }
  }

  /** The settings of voice as they are now, the way the Telegram bridge and the doctor ask the same choice the button does */
  fun settings(): VoiceEngines.Settings = VoiceEngines.Settings(
    manualModel = VibeAgentSettings.voiceModelPath,
    language = VibeAgentSettings.telegramVoiceLanguage,
    pythonConsent = VibeAgentSettings.voicePythonConsent,
    localEnabled = VibeAgentSettings.voiceLocalEnabled,
    modelsPath = VibeAgentSettings.voiceModelsPath,
    keepAliveSec = VibeAgentSettings.voiceKeepAliveSec,
  )

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
