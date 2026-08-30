// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

import com.vibe.agent.i18n.VibeI18n.t

/**
 * SRT into a transcript the model can quote by time.
 *
 * Timestamps are kept on purpose: an answer that says "at 4:12 a chart is shown" can be checked,
 * one that says "somewhere in the middle" cannot. Cues are merged into paragraphs because subtitle files
 * break lines by screen width, not by sense, and a model reading 900 two-word lines spends its
 * context on formatting.
 */
object Subtitles {
  data class Cue(val startSec: Double, val text: String)

  fun parse(srt: String): List<Cue> {
    val cues = ArrayList<Cue>()
    var pendingStart: Double? = null
    val text = StringBuilder()
    for (raw in srt.lineSequence()) {
      val line = raw.trim().removePrefix("﻿")
      val time = TIME_LINE.find(line)
      when {
        time != null -> {
          flush(cues, pendingStart, text)
          pendingStart = seconds(time.groupValues[1], time.groupValues[2], time.groupValues[3], time.groupValues[4])
        }
        line.isEmpty() -> {
          flush(cues, pendingStart, text)
          pendingStart = null
        }
        line.toIntOrNull() != null && text.isEmpty() -> {} // cue number
        else -> {
          if (text.isNotEmpty()) text.append(' ')
          // Auto-generated subtitles are full of inline tags; they carry nothing for a reader.
          text.append(line.replace(TAG, "").trim())
        }
      }
    }
    flush(cues, pendingStart, text)
    return cues
  }

  /** Merges cues into readable paragraphs, each stamped with the time of its first cue. */
  fun transcript(cues: List<Cue>, maxChars: Int = DEFAULT_MAX_CHARS, paragraphSec: Double = PARAGRAPH_SECONDS): String {
    if (cues.isEmpty()) return ""
    val out = StringBuilder()
    var paragraphStart = cues.first().startSec
    val paragraph = StringBuilder()
    fun flushParagraph() {
      if (paragraph.isBlank()) return
      out.append('[').append(stamp(paragraphStart)).append("] ").append(paragraph.trim()).append('\n')
      paragraph.setLength(0)
    }
    for (cue in cues) {
      if (cue.startSec - paragraphStart >= paragraphSec) {
        flushParagraph()
        paragraphStart = cue.startSec
      }
      if (paragraph.isNotEmpty()) paragraph.append(' ')
      paragraph.append(cue.text)
      if (out.length + paragraph.length > maxChars) break
    }
    flushParagraph()
    val result = out.toString().trimEnd()
    return if (result.length <= maxChars) result else result.take(maxChars) + "\n" + t("watch.transcript.clipped")
  }

  fun stamp(seconds: Double): String {
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
  }

  private fun flush(cues: MutableList<Cue>, start: Double?, text: StringBuilder) {
    val body = text.toString().trim()
    text.setLength(0)
    if (start == null || body.isEmpty()) return
    // Auto-subtitles repeat the previous line as a rolling window — the repetition is noise.
    if (cues.lastOrNull()?.text == body) return
    cues.add(Cue(start, body))
  }

  private fun seconds(h: String, m: String, s: String, ms: String): Double =
    h.toInt() * 3600.0 + m.toInt() * 60.0 + s.toInt() + ms.toInt() / 1000.0

  private val TIME_LINE = Regex("^(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->")
  private val TAG = Regex("<[^>]*>")

  const val DEFAULT_MAX_CHARS = 40_000
  private const val PARAGRAPH_SECONDS = 20.0
}
