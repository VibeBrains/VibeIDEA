// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the writes an ACP agent makes with its OWN tools into journal entries
 *
 * Claude Code writes files itself instead of calling `fs/write_text_file`, so nothing reaches the client before the file
 * has changed; the only trace is the tool call: a diff in its `content` and the status that follows.
 * The review needs a baseline (the text before) and the result (the text after): both are taken from the disk, never from the diff
 *
 * Baseline: the file as it was when the call was first seen ([snapshot], at the announce or at the permission request),
 * the first touch per file wins. An absent file is a snapshot too: it means the agent created it.
 * Without a snapshot the diff may still tell: an edit's `oldText`/`newText` are fragments, so the baseline is rebuilt by putting
 * `oldText` back in place of `newText` in the file as it is now. A diff that cannot be rebuilt is skipped — a guessed
 * baseline would make «reject» destroy the file.
 *
 * Pure but for the reader of the disk, which is injected. One instance per turn.
 */
class ExternalEditCapture(private val read: (String) -> String? = ::readDisk) {
  /** The file's text before the agent touched it; [text] is null when the file did not exist */
  private data class Snapshot(val text: String?)

  /** What to record in the journal */
  data class Edit(val path: String, val before: String?, val after: String)

  private val snapshots = ConcurrentHashMap<String, Snapshot>()

  /** Remembers the files as they are now; a file already remembered keeps its first snapshot */
  fun snapshot(paths: Collection<String>) {
    for (path in paths) snapshots.computeIfAbsent(path) { Snapshot(read(path)) }
  }

  /**
   * The edits a finished call made, by the text on the disk now
   * [diffs] carry resolved paths; a path in [paths] without a diff is still recorded when it has a snapshot
   * A snapshot is spent: the next call on the same file starts from what this one left
   */
  fun settle(paths: Collection<String>, diffs: List<ToolDiff>): List<Edit> {
    val edits = ArrayList<Edit>()
    for (path in paths.distinct()) {
      val snap = snapshots.remove(path)
      val after = read(path) ?: continue
      val before = if (snap != null) snap.text else rebuild(after, diffs.firstOrNull { it.path == path }) ?: continue
      if (before == after) continue
      edits.add(Edit(path, before, after))
    }
    return edits
  }

  private fun rebuild(after: String, diff: ToolDiff?): String? {
    val old = diff?.oldText ?: return null
    val new = diff.newText
    if (new.isEmpty()) return null
    val at = after.indexOf(new)
    return if (at < 0) null else after.replaceRange(at, at + new.length, old)
  }

  companion object {
    /** Longest text of a diff in the permission question: the question is read, not scrolled through a file */
    const val MAX_PREVIEW_CHARS = 4000

    fun readDisk(path: String): String? = runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()

    /** A diff as text for the permission question: `path`, then removed lines with «-» and added lines with «+», capped */
    fun previewText(diffs: List<ToolDiff>): String {
      val out = StringBuilder()
      for (d in diffs) {
        out.append(d.path).append('\n')
        d.oldText?.lineSequence()?.forEach { out.append("- ").append(it).append('\n') }
        d.newText.lineSequence().forEach { out.append("+ ").append(it).append('\n') }
        out.append('\n')
        if (out.length > MAX_PREVIEW_CHARS) break
      }
      val text = out.toString().trimEnd()
      return if (text.length <= MAX_PREVIEW_CHARS) text else text.take(MAX_PREVIEW_CHARS - 1) + "…"
    }
  }
}
