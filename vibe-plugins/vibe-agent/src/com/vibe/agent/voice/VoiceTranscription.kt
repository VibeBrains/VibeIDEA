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
 *
 * Two transcribers answer to «whisper», and they share nothing but the name. openai-whisper
 * (`whisper`, Python) takes the audio as an operand and fetches its own model; whisper.cpp
 * (`whisper-cli`) takes `-f` and `-m` and a model file the person chose. Until 11.09.2026 both got
 * openai-whisper's flags, so with whisper.cpp installed — the install our own hint suggested — every
 * note ended in the usage text and «ни слова не разобрано».
 */
object VoiceTranscription {
  /** Which of the two, because each wants a different command line. */
  enum class Kind { OPENAI_WHISPER, WHISPER_CPP }

  /** @property model the ggml model file; whisper.cpp only. */
  data class Transcriber(val binary: String, val kind: Kind = Kind.OPENAI_WHISPER, val model: String? = null)

  const val OPENAI_BINARY = "whisper"
  const val CPP_BINARY = "whisper-cli"

  /** whisper.cpp assumes English when told nothing; «auto» is what openai-whisper does unasked. */
  const val AUTO_LANGUAGE = "auto"

  /**
   * The transcriber for this audio, or null when the machine has none that can run.
   *
   * A WAV from the IDE microphone goes to whisper.cpp when it is set up: the model is local, loaded
   * once and fast. Anything else — a Telegram note is Opus in OGG — goes to openai-whisper first,
   * which decodes through ffmpeg; whisper.cpp is the fallback there.
   *
   * @param modelPath the whisper.cpp model from the settings; without one whisper.cpp cannot run.
   */
  fun find(
    modelPath: String?,
    wav: Boolean,
    lookup: (String) -> String? = WatchTools::find,
    hasFile: (String) -> Boolean = { File(it).isFile },
  ): Transcriber? {
    val model = modelPath?.trim()?.takeIf { it.isNotEmpty() && hasFile(it) }
    val cpp = model?.let { m -> lookup(CPP_BINARY)?.let { Transcriber(it, Kind.WHISPER_CPP, m) } }
    val openai = lookup(OPENAI_BINARY)?.let { Transcriber(it, Kind.OPENAI_WHISPER) }
    return if (wav) cpp ?: openai else openai ?: cpp
  }

  /** whisper.cpp is installed but has no model file — the one case where the fix is a setting, not an install. */
  fun needsModel(
    modelPath: String?,
    lookup: (String) -> String? = WatchTools::find,
    hasFile: (String) -> Boolean = { File(it).isFile },
  ): Boolean = lookup(CPP_BINARY) != null && modelPath?.trim()?.takeIf { it.isNotEmpty() }?.let(hasFile) != true

  /**
   * The command that writes a plain-text transcript next to the audio.
   *
   * A file and an explicit place rather than stdout: both print progress, timings and warnings
   * there, and a transcript scraped out of that mixture eventually carries a line of somebody's log
   * into the task.
   */
  fun command(transcriber: Transcriber, audio: File, outputDir: File, language: String?): List<String> = buildList {
    // A language given up front saves the detection pass and stops a short note in one language
    // being decoded as another — the classic failure on «ага, поехали».
    val lang = language?.trim()?.takeIf { it.isNotEmpty() }
    add(transcriber.binary)
    when (transcriber.kind) {
      Kind.OPENAI_WHISPER -> {
        add(audio.absolutePath)
        add("--output_format"); add("txt")
        add("--output_dir"); add(outputDir.absolutePath)
        lang?.let { add("--language"); add(it) }
      }
      Kind.WHISPER_CPP -> {
        add("-m"); add(requireNotNull(transcriber.model) { "whisper.cpp needs a model file" })
        add("-f"); add(audio.absolutePath)
        add("-otxt"); add("-of"); add(File(outputDir, audio.nameWithoutExtension).absolutePath)
        add("-np"); add("-nt")
        add("-l"); add(lang ?: AUTO_LANGUAGE)
      }
    }
  }

  /** Where [command] leaves the transcript — the same place for both transcribers. */
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
