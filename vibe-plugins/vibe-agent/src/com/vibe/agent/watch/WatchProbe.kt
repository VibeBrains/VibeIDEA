// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deciding whether a source actually has a picture — the authoritative answer, as opposed to the
 * guess in [WatchInput].
 *
 * Positive evidence of video ALWAYS wins over the name-based hint. The first version of this rule
 * in VibeIDE was `hint || audioOnly`, and a remote `.ogg` carrying a Theora stream silently lost
 * its video: the file was named like audio, so it was treated as audio.
 */
object WatchProbe {
  private val json = Json { ignoreUnknownKeys = true }

  /** `yt-dlp --dump-single-json`: audio-only when every format reports no video codec. */
  fun remoteIsAudioOnly(dumpJson: String, hint: WatchInput.Kind): Boolean {
    val root = runCatching { json.parseToJsonElement(dumpJson).jsonObject }.getOrNull() ?: return hint == WatchInput.Kind.AUDIO
    val formats = runCatching { root["formats"]!!.jsonArray }.getOrNull()
    val codecs = formats?.mapNotNull { runCatching { it.jsonObject["vcodec"]?.jsonPrimitive?.contentOrNull }.getOrNull() }.orEmpty()
    if (codecs.isEmpty()) {
      // The probe told us nothing (a generic extractor): only then may the name break the tie.
      return hint == WatchInput.Kind.AUDIO
    }
    return codecs.all { it == "none" }
  }

  /**
   * `ffmpeg -i <file>` with no output file exits non-zero BY DESIGN — the banner on stderr is the
   * answer, and treating the exit code as failure would reject every readable file.
   *
   * @return null when the file could not be read at all (no stream lines), so the caller can show
   *         ffmpeg's own words instead of inventing "no audio track".
   */
  /**
   * Whether a video stream is HDR: PQ (`smpte2084`) or HLG (`arib-std-b67`).
   *
   * The `ffmpeg -i` banner names the transfer in the stream line, e.g.
   * `yuv420p10le(tv, bt2020nc/bt2020/smpte2084)`. Without a translation to SDR the frames look washed
   * out, and the model judges pale grey pictures instead of what the person sees (handoff, 03.11.2026).
   */
  fun isHdr(ffmpegStderr: String): Boolean = ffmpegStderr.lineSequence()
    .filter { it.contains("Stream #") && it.contains(": Video:") }
    .any { line ->
      HDR_TRANSFERS.any { it in line.lowercase() }
    }

  fun localIsAudioOnly(ffmpegStderr: String): Boolean? {
    val streams = ffmpegStderr.lineSequence().filter { it.contains("Stream #") }.toList()
    if (streams.isEmpty()) return null
    val video = streams.filter { it.contains(": Video:") }
    // A cover image is a video stream in the container and not a picture to watch.
    val realVideo = video.filterNot { it.contains("(attached pic)") }
    return realVideo.isEmpty()
  }

  /** Transfer functions that mean HDR: PQ, HLG, and Dolby Vision's own marker when the banner carries it. */
  private val HDR_TRANSFERS = listOf("smpte2084", "arib-std-b67", "dovi")
}
