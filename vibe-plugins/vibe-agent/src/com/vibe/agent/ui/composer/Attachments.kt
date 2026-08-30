// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Component
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Image attachments: system chooser, clipboard paste and file drop all funnel into
 * [ImageAttachment]. PDF is deliberately absent (text extraction needs a PDF library —
 * a separate dependency decision, tracked in the roadmap).
 */
object Attachments {
  private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")
  private val MIME_BY_EXTENSION = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
  )
  private val PASTED_NAME: String get() = t("attachments.pastedName")
  private const val PASTED_MIME = "image/png"
  /** The strictest limit among supported vendors (Anthropic: 5 MB per image); larger files are refused at intake. */
  const val MAX_IMAGE_MB = 5L
  const val MAX_IMAGE_BYTES = MAX_IMAGE_MB * 1024 * 1024

  fun isImageFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MIME_BY_EXTENSION

  fun fromVirtualFile(file: VirtualFile): ImageAttachment? {
    val mime = MIME_BY_EXTENSION[file.extension?.lowercase()] ?: return null
    return ImageAttachment(file.name, mime, file.contentsToByteArray())
  }

  fun fromFile(file: File): ImageAttachment? {
    val mime = MIME_BY_EXTENSION[file.extension.lowercase()] ?: return null
    return ImageAttachment(file.name, mime, file.readBytes())
  }

  /** Opens the native chooser (images only, multiple); bytes are read off the EDT, [onPicked] runs on EDT. */
  fun choose(project: Project, parent: Component, onPicked: (List<ImageAttachment>) -> Unit) {
    val descriptor = FileChooserDescriptorFactory.multiFiles()
      .withTitle(t("attachments.chooserTitle"))
      .withExtensionFilter(t("attachments.images"), *IMAGE_EXTENSIONS.toTypedArray())
    FileChooser.chooseFiles(descriptor, project, parent, null) { files ->
      loadAsync(files.map { VfsUtilCore.virtualToIoFile(it) }, onPicked)
    }
  }

  /** Reads image files on a pooled thread (disk may be slow/remote), then hands the result to [onLoaded] on EDT. */
  fun loadAsync(files: List<File>, onLoaded: (List<ImageAttachment>) -> Unit) {
    if (files.isEmpty()) return
    ApplicationManager.getApplication().executeOnPooledThread {
      val loaded = files.mapNotNull { runCatching { fromFile(it) }.getOrNull() }
      ApplicationManager.getApplication().invokeLater({ onLoaded(loaded) }, ModalityState.any())
    }
  }

  /** An image from the clipboard / a drop, or null when the transferable carries none. */
  fun fromTransferable(t: Transferable): ImageAttachment? {
    if (!t.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
    val image = t.getTransferData(DataFlavor.imageFlavor) as? Image ?: return null
    val buffered = image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
      val g = it.createGraphics()
      g.drawImage(image, 0, 0, null)
      g.dispose()
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(buffered, "png", out)
    return ImageAttachment(PASTED_NAME, PASTED_MIME, out.toByteArray())
  }
}
