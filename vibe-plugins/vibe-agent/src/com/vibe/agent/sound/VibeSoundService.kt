// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.sound

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.vibe.agent.settings.VibeChatSettings
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import kotlin.math.PI
import kotlin.math.sin

/**
 * The notification sound.
 *
 * The default chime is SYNTHESISED rather than shipped as a file: two short notes written into a
 * PCM buffer. That costs no asset, no packaging change and no licence question, and it sounds the
 * same on every platform — a bundled mp3 would need a decoder that the JDK does not have.
 *
 * A custom file is validated by TRYING TO OPEN IT, not by its extension: what the JDK can play
 * depends on the machine, and a file that a person picked and that then silently does nothing is
 * worse than an honest "этот формат здесь не читается".
 */
@Service(Service.Level.APP)
class VibeSoundService {
  private val log = logger<VibeSoundService>()
  private val lastPlayedMs = AtomicLong(0)

  fun play(event: SoundPolicy.Event, project: Project?) {
    val settings = SoundPolicy.Settings(
      enabled = VibeChatSettings.soundEnabled,
      onTurnFinished = VibeChatSettings.soundOnTurnFinished,
      onTurnStopped = VibeChatSettings.soundOnTurnStopped,
      onAwaitingPermission = VibeChatSettings.soundOnAwaitingPermission,
      muteWhenFocused = VibeChatSettings.soundMuteWhenFocused,
    )
    val focused = project?.let { WindowManager.getInstance().getFrame(it)?.isActive == true } ?: false
    val now = System.currentTimeMillis()
    if (!SoundPolicy.shouldPlay(event, settings, focused, now, lastPlayedMs.get())) return
    lastPlayedMs.set(now)
    ApplicationManager.getApplication().executeOnPooledThread { playNow() }
  }

  /** Preview from the settings page: bypasses the gates on purpose — the person just asked for it. */
  fun preview(): Result<Unit> = runCatching { playNow() }

  private fun playNow() {
    val custom = VibeChatSettings.soundCustomPath.trim()
    if (custom.isNotEmpty()) {
      val played = runCatching { playFile(File(custom)) }.getOrElse { error ->
        log.warn("не удалось проиграть свой звук ($custom): ${error.message}")
        false
      }
      if (played) return
    }
    runCatching { playChime() }.onFailure { log.warn("звук не воспроизведён: ${it.message}") }
  }

  private fun playFile(file: File): Boolean {
    if (!file.isFile) return false
    AudioSystem.getAudioInputStream(file).use { stream ->
      val clip = AudioSystem.getClip()
      clip.open(stream)
      applyVolume(clip)
      clip.start()
      // The clip plays on its own thread; the pooled thread waits so the resource is released.
      Thread.sleep(minOf(clip.microsecondLength / 1000, MAX_CUSTOM_MS))
      clip.close()
    }
    return true
  }

  private fun playChime() {
    val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
    val line = AudioSystem.getSourceDataLine(format)
    line.open(format)
    applyVolume(line)
    line.start()
    line.write(tone(NOTE_ONE_HZ, NOTE_MS), 0, NOTE_MS * SAMPLE_RATE / 1000 * 2)
    line.write(tone(NOTE_TWO_HZ, NOTE_MS), 0, NOTE_MS * SAMPLE_RATE / 1000 * 2)
    line.drain()
    line.close()
  }

  /** One note as 16-bit PCM, with a short fade so it does not click at the edges. */
  private fun tone(frequencyHz: Double, millis: Int): ByteArray {
    val samples = millis * SAMPLE_RATE / 1000
    val buffer = ByteArray(samples * 2)
    val fade = samples / 8
    for (i in 0 until samples) {
      val envelope = when {
        i < fade -> i.toDouble() / fade
        i > samples - fade -> (samples - i).toDouble() / fade
        else -> 1.0
      }
      val value = (sin(2 * PI * frequencyHz * i / SAMPLE_RATE) * envelope * Short.MAX_VALUE * 0.35).toInt()
      buffer[i * 2] = (value and 0xFF).toByte()
      buffer[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
    return buffer
  }

  private fun applyVolume(line: javax.sound.sampled.Line) {
    val volume = VibeChatSettings.soundVolume.coerceIn(0, 100) / 100.0
    if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
    val control = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
    // Gain is in decibels: a linear slider maps through log10, otherwise 50% sounds like 95%.
    val gain = if (volume <= 0.0001) control.minimum else (20.0 * Math.log10(volume)).toFloat()
    control.value = gain.coerceIn(control.minimum, control.maximum)
  }

  companion object {
    private const val SAMPLE_RATE = 44_100
    private const val NOTE_MS = 90
    private const val NOTE_ONE_HZ = 880.0
    private const val NOTE_TWO_HZ = 1174.7
    private const val MAX_CUSTOM_MS = 5_000L

    fun getInstance(): VibeSoundService = ApplicationManager.getApplication().service()

    /** Can the JDK on THIS machine play the file? Asked by opening it, not by looking at the name. */
    fun canPlay(path: String): Result<Unit> = runCatching {
      val file = File(path.trim())
      require(file.isFile) { "файл не найден" }
      require(file.length() <= MAX_FILE_BYTES) { "файл больше 5 МБ" }
      AudioSystem.getAudioInputStream(file).close()
    }

    const val MAX_FILE_BYTES = 5L * 1024 * 1024
  }
}
