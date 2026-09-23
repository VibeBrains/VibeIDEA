// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.ide.dnd.FileCopyPasteUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * What the clipboard holds and what to do with it — one decision for every entry point.
 *
 * Paste has TWO entry points: the Swing `TransferHandler` (which also serves drag and drop) and the IDE's own paste
 * action (`$Paste` through `PasteProvider`). With the decision living in the first one only, an image was lost wherever
 * the keystroke did not reach it — and from outside that looks like "paste does nothing", without a single message.
 *
 * So the decision is a pure function here: both entry points ask it and cannot drift apart. It is also testable
 * without a window, a clipboard or a keyboard.
 */
object ClipboardContent {
  /** What the clipboard carries. Checked from the most specific kind to the most general one. */
  sealed interface Kind {
    /** Files: images become attachments, PDFs become text, anything else becomes a project file reference. */
    data class Files(val files: List<File>) : Kind

    /** A ready image (a screenshot, a copy from a browser). */
    data class Picture(val image: ImageAttachment) : Kind

    /** Nothing of ours: plain text paste, done by the input field itself. */
    data object Text : Kind
  }

  /**
   * Classify the clipboard contents.
   *
   * The priority rule is stated here once: **an image wins over text next to it**. Accepting an image only when
   * there is NO text would break the most common cases, since browsers and messengers put the image's address or
   * caption next to it. Someone copying an image wants the image; the text beside it is a trait of the source, not
   * a choice.
   */
  fun of(transferable: Transferable?): Kind {
    if (transferable == null) return Kind.Text
    if (runCatching { FileCopyPasteUtil.isFileListFlavorAvailable(transferable.transferDataFlavors) }.getOrDefault(false)) {
      val files = runCatching { FileCopyPasteUtil.getFileList(transferable).orEmpty() }.getOrDefault(emptyList())
      if (files.isNotEmpty()) return Kind.Files(files)
    }
    if (runCatching { transferable.isDataFlavorSupported(DataFlavor.imageFlavor) }.getOrDefault(false)) {
      // Decoding may fail (a foreign format, broken data): then handing the paste to text is more honest than
      // swallowing the keystroke and showing nothing.
      Attachments.fromTransferable(transferable)?.let { return Kind.Picture(it) }
    }
    return Kind.Text
  }

  /**
   * Whether the contents look like ours — BY DATA FLAVORS, without reading the data itself.
   *
   * A cheap check separate from [of]: it runs on every mouse move during a drag and on every update of the paste
   * action, while [of] encodes a PNG to answer. Erring towards "yes" is safe here: the real decoding happens anyway,
   * and a failed one hands the paste back to text.
   */
  fun carriesOurs(transferable: Transferable?): Boolean {
    if (transferable == null) return false
    return runCatching {
      transferable.isDataFlavorSupported(DataFlavor.imageFlavor) ||
      FileCopyPasteUtil.isFileListFlavorAvailable(transferable.transferDataFlavors)
    }.getOrDefault(false)
  }
}
