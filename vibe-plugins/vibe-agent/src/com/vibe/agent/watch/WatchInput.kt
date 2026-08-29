// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

/**
 * What kind of thing the user pointed at — decided cheaply, before anything is downloaded.
 *
 * The verdict is needed early for one reason: an audio source needs no vision model, and gating a
 * podcast behind "switch to a vision model" would be a refusal for no reason. It is a HINT, not the
 * truth: the authoritative answer comes from probing the file with a tool inside the pipeline, and
 * positive evidence of video always beats this guess (a `.ogg` with a Theora stream is video).
 */
object WatchInput {
  enum class Kind { VIDEO, AUDIO, UNKNOWN }

  private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "aiff", "amr")
  private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "mpg", "mpeg", "ts")

  fun isUrl(input: String): Boolean {
    val text = input.trim().lowercase()
    return text.startsWith("http://") || text.startsWith("https://")
  }

  /**
   * @return the kind, or [Kind.UNKNOWN] for a platform page with no extension — treated
   *         conservatively as video by the caller.
   */
  fun classify(input: String): Kind {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return Kind.UNKNOWN
    // Query and fragment are stripped ONLY for URLs: `#` is legal in file names on every OS and
    // `?` on unix, so cutting a local path there turned real files into "unknown" (VibeIDE lesson).
    val path = if (isUrl(trimmed)) trimmed.substringBefore('?').substringBefore('#') else trimmed
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    if (!name.contains('.')) return Kind.UNKNOWN
    return when (name.substringAfterLast('.').lowercase()) {
      in AUDIO_EXTENSIONS -> Kind.AUDIO
      in VIDEO_EXTENSIONS -> Kind.VIDEO
      else -> Kind.UNKNOWN
    }
  }

  /** `/watch <источник> [вопрос]` — splits the command line the composer intercepted. */
  data class Command(val source: String, val question: String)

  fun parse(line: String): Command? {
    val text = line.trim()
    if (!text.startsWith(PREFIX)) return null
    val rest = text.removePrefix(PREFIX).trim()
    if (rest.isEmpty()) return null
    val source = rest.substringBefore(' ').trim()
    val question = rest.removePrefix(source).trim()
    return Command(source, question)
  }

  const val PREFIX = "/watch"
}
