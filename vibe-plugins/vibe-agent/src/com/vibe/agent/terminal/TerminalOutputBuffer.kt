// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.terminal

/**
 * Rolling capture of a terminal's stdout+stderr with an ACP `outputByteLimit`.
 * Per the protocol, when output exceeds the limit the client truncates from the
 * START (retains the tail) at a character boundary and reports `truncated=true`.
 * Pure and synchronized so the process-reader thread and the request thread can
 * both touch it.
 */
class TerminalOutputBuffer(private val byteLimit: Long?) {
  private val sb = StringBuilder()
  private var truncated = false
  /** Running UTF-8 size of [sb] — maintained incrementally so append() stays O(chunk), not O(total). */
  private var byteCount = 0L

  @Synchronized
  fun append(chunk: String) {
    sb.append(chunk)
    byteCount += utf8Size(chunk)
    enforceLimit()
  }

  @Synchronized
  fun snapshot(): Pair<String, Boolean> = sb.toString() to truncated

  private fun enforceLimit() {
    val limit = byteLimit ?: return
    if (limit <= 0 || byteCount <= limit) return
    truncated = true
    // Drop whole code points from the front until within the byte budget.
    var start = 0
    while (start < sb.length && byteCount > limit) {
      val cp = sb.codePointAt(start)
      byteCount -= utf8Bytes(cp)
      start += Character.charCount(cp)
    }
    sb.delete(0, start)
  }

  private fun utf8Size(cs: CharSequence): Long {
    var total = 0L
    var i = 0
    while (i < cs.length) {
      val cp = Character.codePointAt(cs, i)
      total += utf8Bytes(cp)
      i += Character.charCount(cp)
    }
    return total
  }

  private fun utf8Bytes(cp: Int): Int = when {
    cp <= 0x7F -> 1
    cp <= 0x7FF -> 2
    cp <= 0xFFFF -> 3
    else -> 4
  }
}
