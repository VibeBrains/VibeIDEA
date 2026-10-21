// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import java.io.File

/**
 * Where the source of a watch request actually is.
 *
 * People type paths the way they do in a terminal: `1.webm`, `./1.webm`, `sub/2.webm`, `~/Desktop/x.mp4`.
 * A relative one is meant from the project root, because that is where the person sees the file in the IDE
 * Pure on purpose: every branch is a case worth a test, and none of them needs a project
 */
object WatchPath {
  sealed interface Resolved {
    /** A URL, an absolute path or a relative one that was found; [value] goes to the pipeline as is */
    data class Found(val value: String) : Resolved

    /** A relative path that exists under none of [roots] */
    data class Missing(val raw: String, val roots: List<String>) : Resolved
  }

  /**
   * @param roots the project roots in the order they are tried; the first that holds the file wins
   * @param home the home folder `~` stands for
   * @param isFile whether a file exists; replaceable so the rules run without a disk
   */
  fun resolve(raw: String, roots: List<String>, home: String, isFile: (String) -> Boolean = { File(it).isFile }): Resolved {
    val text = unquote(raw.trim())
    if (WatchInput.isUrl(text)) return Resolved.Found(text)
    if (text == "~" || text.startsWith("~/") || text.startsWith("~\\")) {
      return Resolved.Found(File(home, text.drop(1).trimStart('/', '\\')).path)
    }
    if (File(text).isAbsolute) return Resolved.Found(text)
    val relative = text.removePrefix("./").removePrefix(".\\")
    for (root in roots) {
      val candidate = File(root, relative).toPath().normalize().toString()
      if (isFile(candidate)) return Resolved.Found(candidate)
    }
    // No root at all: the pipeline keeps the old behaviour and judges the path as typed
    return if (roots.isEmpty()) Resolved.Found(text) else Resolved.Missing(text, roots)
  }

  /** The name of a project root for a message: its folder, which is what the person calls the project */
  fun rootName(root: String): String = File(root).name.ifEmpty { root }

  /** Quotes around a path with spaces are the way to type it; they are not part of the name */
  fun unquote(text: String): String {
    if (text.length >= 2) {
      val first = text.first()
      if ((first == '"' || first == '\'') && text.last() == first) return text.substring(1, text.length - 1)
    }
    return text
  }
}
