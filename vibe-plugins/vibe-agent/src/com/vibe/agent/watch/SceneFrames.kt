// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

/**
 * Frames by scene change, and the two traps that make a naive version return nothing.
 *
 * First: a static talking-head or a screencast does not cross even a low scene threshold, so the
 * filter ALWAYS anchors the first frame (`eq(n,0)+…`) and the pipeline retries once at 0.1 when it
 * got almost nothing. Without the anchor the answer to "what is on screen" is an empty list.
 *
 * Second: ffmpeg prints the frame size as `s:1280x720` in 6.x and `s=1280x720` in 8.x. VibeIDE's
 * live smoke caught exactly this as a production bug — a parser written for one major silently
 * produced zero frames on the other, which looks identical to "the video has no scenes".
 */
object SceneFrames {
  data class Frame(val index: Int, val timestampSec: Double)

  /** The filter string handed to ffmpeg. The anchor is not optional — see the class comment. */
  fun filter(threshold: Double, frameHeight: Int): String =
    "select='eq(n,0)+gt(scene,$threshold)',scale=-2:$frameHeight,showinfo"

  /** Parsed from ffmpeg's `showinfo` output on stderr; tolerant of both major-version formats. */
  fun parseShowinfo(output: String): List<Frame> {
    val result = ArrayList<Frame>()
    for (line in output.lineSequence()) {
      if (!line.contains("Parsed_showinfo")) continue
      val n = N_FIELD.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
      val time = TIME_FIELD.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
      // `s:WxH` (ffmpeg 6.x) or `s=WxH` (8.x) — accepting only one silently yields zero frames.
      if (!SIZE_FIELD.containsMatchIn(line)) continue
      result.add(Frame(n, time))
    }
    return result
  }

  /**
   * Thins frames down to [max], evenly ACROSS TIME rather than by index: scene changes cluster in
   * edited passages, and taking the first N would return the intro and miss the whole second half.
   */
  fun thin(frames: List<Frame>, max: Int): List<Frame> {
    if (max <= 0 || frames.isEmpty()) return emptyList()
    if (frames.size <= max) return frames
    val first = frames.first().timestampSec
    val last = frames.last().timestampSec
    if (last <= first) return frames.take(max)
    val step = (last - first) / (max - 1).coerceAtLeast(1)
    val picked = LinkedHashMap<Int, Frame>()
    for (i in 0 until max) {
      val wanted = first + step * i
      val nearest = frames.minByOrNull { kotlin.math.abs(it.timestampSec - wanted) } ?: continue
      picked[nearest.index] = nearest
    }
    return picked.values.sortedBy { it.timestampSec }
  }

  /** True when the pass found so little that the threshold, not the video, is the problem. */
  fun needsRetry(frames: List<Frame>): Boolean = frames.size < MIN_USEFUL_FRAMES

  const val DEFAULT_SCENE_THRESHOLD = 0.3
  const val RETRY_SCENE_THRESHOLD = 0.1
  const val MIN_USEFUL_FRAMES = 3

  private val N_FIELD = Regex("\\bn:\\s*(\\d+)")
  private val TIME_FIELD = Regex("pts_time:\\s*([\\d.]+)")
  private val SIZE_FIELD = Regex("\\bs[:=]\\d+x\\d+")
}
