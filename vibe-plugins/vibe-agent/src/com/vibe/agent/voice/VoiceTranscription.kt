// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.voice

import com.vibe.agent.watch.WatchTools
import java.io.File

/**
 * Turning recorded speech into text — the half that has nothing to do with where the audio came from.
 *
 * It lived inside the Telegram bridge while the phone was the only source. The microphone in the
 * IDE made that a lie: the same transcriber, the same filler filter and the same «слишком коротко,
 * это кашель» rule serve both, and two copies of the rule would have drifted the first time one of
 * them was fixed.
 *
 * The transcriber is NOT bundled: shipping model weights and GPL builds is a licensing and release
 * problem this fork has not solved. So the feature uses what the machine has, and when the machine
 * has nothing it says exactly what to install — silence would be the worst of the three behaviours.
 */
object VoiceTranscription {
  /** Transcribers we know how to call, in order of preference. */
  private val CANDIDATES = listOf("whisper-cli", "whisper")

  data class Transcriber(val binary: String)

  fun find(): Transcriber? = CANDIDATES.firstNotNullOfOrNull { WatchTools.find(it)?.let { path -> Transcriber(path) } }

  /**
   * The command that writes a plain-text transcript next to the audio.
   *
   * `--output_format txt` and an explicit directory rather than parsing stdout: whisper prints
   * progress, timings and warnings there, and a transcript scraped out of that mixture eventually
   * carries a line of somebody's log into the task.
   */
  fun command(transcriber: Transcriber, audio: File, outputDir: File, language: String?): List<String> = buildList {
    add(transcriber.binary)
    add(audio.absolutePath)
    add("--output_format"); add("txt")
    add("--output_dir"); add(outputDir.absolutePath)
    // A language given up front saves the detection pass and stops a short note in one language
    // being decoded as another — the classic failure on «ага, поехали».
    language?.takeIf { it.isNotBlank() }?.let { add("--language"); add(it) }
  }

  /** Where [command] leaves the transcript. */
  fun outputFile(audio: File, outputDir: File): File = File(outputDir, audio.nameWithoutExtension + ".txt")

  /**
   * The text as a task, or null when there is nothing to run.
   *
   * Whisper on silence produces its own filler («Продолжение следует…», «Thank you.») — a known
   * artefact of its training data. Running such a «task» would be an agent started by noise.
   */
  fun taskFrom(transcript: String): String? {
    val text = transcript.lines().joinToString(" ") { it.trim() }.trim()
    if (text.length < MIN_TASK_CHARS) return null
    if (FILLER.any { it.containsMatchIn(text) } && text.length < FILLER_MAX_CHARS) return null
    return text
  }

  /** Shorter than this is not a task — it is a cough. */
  const val MIN_TASK_CHARS = 8

  private const val FILLER_MAX_CHARS = 60

  /** Whisper's own hallucinations on silence; detection data, not interface text. */
  private val FILLER = listOf(
    Regex("(?iU)продолжение следует"),
    Regex("(?iU)субтитры (сделал|создавал)"),
    Regex("(?i)^thank you\\.?$"),
    Regex("(?i)^you$"),
  )
}
