// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.watch.WatchTools
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pulls the text out of a PDF with the machine's own `pdftotext` (poppler).
 *
 * The library road was tried and abandoned on evidence: the only PDFBox in this repository lives
 * inside the flexmark bundle, and bundling that to read text would drag an entire markdown and
 * HTML-to-PDF stack into the distribution. The first real build also showed the honest version of
 * the problem — the jar compiled against was not shipped at all, and the feature was dead in the
 * installer while every test stayed green.
 *
 * So PDF joins the company it belongs to: language servers, debug adapters, `yt-dlp`, `ffmpeg`,
 * whisper. The client is ours, the engine is the machine's, and a missing engine is named out loud
 * with the one line that installs it.
 *
 * Everything that can fail here fails quietly if allowed to — an encrypted file, a scan without a
 * text layer, a tool that is not installed. Each returns a NAMED failure, because an empty
 * attachment reaches the model as a document that exists and says nothing.
 */
object PdfExtract {
  class Document(val text: String, val pages: Int, val droppedChars: Int, val scanned: Boolean)

  /** Poppler's extractor: present on most Linux machines, `brew install poppler` on macOS. */
  const val BINARY = "pdftotext"

  private const val TIMEOUT_SEC = 60L

  fun isAvailable(): Boolean = WatchTools.find(BINARY) != null

  fun read(file: File, maxChars: Int = PdfText.MAX_CHARS): Result<Document> {
    if (!file.isFile) return Result.failure(IllegalArgumentException(t("pdf.error.missing", "name" to file.name)))
    val tool = WatchTools.find(BINARY)
      ?: return Result.failure(IllegalStateException(t("pdf.error.noTool", "tool" to BINARY)))
    return runCatching {
      // `-q` silences the progress chatter, `-enc UTF-8` stops the output depending on the locale of
      // whoever started the IDE, and `-` sends the text to stdout instead of leaving a file behind.
      val process = ProcessBuilder(tool, "-q", "-enc", "UTF-8", file.absolutePath, "-").start()
      val raw = process.inputStream.bufferedReader().readText()
      val errors = process.errorStream.bufferedReader().readText()
      if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error(t("pdf.error.timeout", "name" to file.name, "seconds" to TIMEOUT_SEC))
      }
      if (process.exitValue() != 0) {
        // The tool says «Incorrect password» for an encrypted file; passing its own words on is
        // more useful than our guess about which of the failures it was.
        error(t("pdf.error.unreadable", "name" to file.name,
                "reason" to errors.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(200)))
      }
      val pages = PdfText.pages(raw)
      val clean = PdfText.clean(raw)
      val trimmed = PdfText.trim(clean, maxChars)
      Document(trimmed.text, pages, trimmed.droppedChars, PdfText.looksScanned(clean, pages))
    }
  }
}
