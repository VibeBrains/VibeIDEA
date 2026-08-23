// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.openapi.vfs.VirtualFile

/**
 * A staged piece of context attached to the next message (VibeIDE "staging selection").
 * Lines are 1-based and inclusive, matching the chip label `name (N1-N2)`.
 */
sealed interface ContextRef {
  val file: VirtualFile
  val label: String

  data class File(override val file: VirtualFile) : ContextRef {
    override val label: String get() = file.name
  }

  data class Folder(override val file: VirtualFile) : ContextRef {
    override val label: String get() = file.name
  }

  data class Selection(override val file: VirtualFile, val fromLine: Int, val toLine: Int, val text: String) : ContextRef {
    override val label: String get() = "${file.name} ($fromLine-$toLine)"
  }

  /** Stable identity for dedup: the same file/range staged twice is one chip. */
  val key: String
    get() = when (this) {
      is File -> "f:${file.path}"
      is Folder -> "d:${file.path}"
      is Selection -> "s:${file.path}#$fromLine-$toLine"
    }
}

/** An image attached to the next message; bytes are kept in memory until the message is sent. */
class ImageAttachment(val name: String, val mimeType: String, val bytes: ByteArray) {
  val sizeKb: Long get() = bytes.size / 1024L
}

/** Everything the composer hands to the sender in one go. */
data class ComposedMessage(
  val text: String,
  val context: List<ContextRef> = emptyList(),
  val images: List<ImageAttachment> = emptyList(),
) {
  val isEmpty: Boolean get() = text.isBlank() && context.isEmpty() && images.isEmpty()
}

/**
 * Messages typed while a turn is running (VibeIDE §9). Pure model: the banner is a view over it.
 * [drain] merges every note into one message for the next turn — notes are joined by a blank
 * line, context and images are concatenated in order with context deduplicated by key.
 */
class InjectionQueue {
  private val notes = ArrayList<ComposedMessage>()
  private val listeners = ArrayList<() -> Unit>()

  val size: Int get() = notes.size
  val isEmpty: Boolean get() = notes.isEmpty()
  fun snapshot(): List<ComposedMessage> = notes.toList()

  fun add(note: ComposedMessage) {
    if (note.isEmpty) return
    notes.add(note)
    fire()
  }

  fun removeAt(index: Int) {
    if (index in notes.indices) {
      notes.removeAt(index)
      fire()
    }
  }

  fun clear() {
    if (notes.isEmpty()) return
    notes.clear()
    fire()
  }

  fun drain(): ComposedMessage? {
    if (notes.isEmpty()) return null
    val merged = merge(notes)
    notes.clear()
    fire()
    return merged
  }

  fun onChange(listener: () -> Unit) { listeners.add(listener) }

  private fun fire() { listeners.forEach { it() } }

  companion object {
    fun merge(notes: List<ComposedMessage>): ComposedMessage = ComposedMessage(
      text = notes.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n"),
      context = notes.flatMap { it.context }.distinctBy { it.key },
      images = notes.flatMap { it.images },
    )
  }
}
