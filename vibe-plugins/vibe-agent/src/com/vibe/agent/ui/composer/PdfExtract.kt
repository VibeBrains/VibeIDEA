// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t
import java.io.File

/**
 * Pulls the text out of a PDF with the PDFBox the platform already ships (it comes with the
 * flexmark bundle, so nothing new is added to the distribution).
 *
 * Everything that can go wrong here goes wrong quietly if allowed to: an encrypted file, a scan
 * without a text layer, a build where the library moved. Each of those returns a NAMED failure
 * instead of an empty string, because an empty attachment reaches the model as a document that
 * exists and says nothing.
 */
object PdfExtract {
  class Document(val text: String, val pages: Int, val droppedChars: Int, val scanned: Boolean)

  fun read(file: File, maxChars: Int = PdfText.MAX_CHARS): Result<Document> {
    if (!file.isFile) return Result.failure(IllegalArgumentException(t("pdf.error.missing", "name" to file.name)))
    return try {
      org.apache.pdfbox.pdmodel.PDDocument.load(file).use { document ->
        // An encrypted document may still open; extracting from it without the owner's permission
        // is exactly the case where a library's leniency should not become ours.
        if (document.isEncrypted) {
          return Result.failure(IllegalStateException(t("pdf.error.encrypted", "name" to file.name)))
        }
        val stripper = org.apache.pdfbox.text.PDFTextStripper()
        val raw = stripper.getText(document)
        val clean = PdfText.clean(raw)
        val pages = document.numberOfPages
        val trimmed = PdfText.trim(clean, maxChars)
        Result.success(Document(trimmed.text, pages, trimmed.droppedChars, PdfText.looksScanned(clean, pages)))
      }
    }
    catch (e: NoClassDefFoundError) {
      // The library lives in the platform's bundle rather than in ours; if a future IDE build
      // stops shipping it, the person deserves a sentence rather than a stack trace.
      Result.failure(IllegalStateException(t("pdf.error.noLibrary")))
    }
    catch (e: Exception) {
      Result.failure(IllegalStateException(t("pdf.error.unreadable", "name" to file.name,
                                             "reason" to (e.message ?: e.javaClass.simpleName))))
    }
  }
}
