// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import com.vibe.agent.watch.WatchTools
import java.io.File

/**
 * A voice message from the phone, turned into a task.
 *
 * Typing a task on a phone is the reason the bridge gets used for «посмотри логи» and not for
 * anything longer. Speaking it is thirty seconds; the obstacle is purely the transcription.
 *
 * The transcriber is NOT bundled, for the same reason the video pipeline's tools are not: shipping
 * model weights and GPL builds is a licensing and release problem this fork has not solved. So the
 * feature uses what the machine has, and when the machine has nothing it says exactly what to
 * install. A voice message silently ignored would be the worst of the three possible behaviours.
 */
object VoiceNote {
  /** Transcribers we know how to call, in order of preference. */
  private val CANDIDATES = listOf("whisper-cli", "whisper")

  /** Telegram hands out a file path first; only then can the file itself be fetched. */
  fun getFileUrl(token: String, fileId: String): String =
    "https://api.telegram.org/bot$token/getFile?file_id=" + java.net.URLEncoder.encode(fileId, "UTF-8")

  fun downloadUrl(token: String, filePath: String): String =
    "https://api.telegram.org/file/bot$token/$filePath"

  /** `file_path` out of the getFile reply, or null when Telegram refused. */
  fun parseFilePath(body: String): String? =
    Regex("\"file_path\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

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
