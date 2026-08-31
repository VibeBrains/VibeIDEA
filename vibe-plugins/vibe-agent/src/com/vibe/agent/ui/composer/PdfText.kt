// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

/**
 * What to do with the text pulled out of a PDF — before anyone decides to send it.
 *
 * A PDF is not an image and not a source file: it is a pile of text with no line structure worth
 * keeping, and it is usually far larger than the window. Two mistakes are easy here and both are
 * silent: sending forty pages that push everything else out of the context, and sending nothing at
 * all because the document is a scan with no text layer. The second is the worse of the two — the
 * model then answers about a document it never saw, and the answer looks perfectly confident.
 *
 * Pure: text in, decisions out. Reading the file is somebody else's problem.
 */
object PdfText {
  /** Every PDF starts with this signature; the extension alone is a claim, not a fact. */
  private val HEADER = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())

  /** Roughly a third of a small window: enough for a real document, not enough to evict everything. */
  const val MAX_CHARS = 60_000

  /** Below this per page the "text layer" is page numbers and headers — that is, a scan. */
  const val MIN_CHARS_PER_PAGE = 40

  /** `pdftotext` separates pages with a form feed; that is where the page count comes from. */
  const val PAGE_BREAK = '\u000C'

  /**
   * Pages, counted from the extractor's own separators.
   *
   * Counting them here rather than asking a second tool (`pdfinfo`) keeps the feature to ONE
   * external binary: every additional required tool is another machine where the feature is absent.
   */
  fun pages(raw: String): Int {
    if (raw.isEmpty()) return 0
    val breaks = raw.count { it == PAGE_BREAK }
    // A trailing form feed closes the last page rather than opening another one.
    return if (raw.endsWith(PAGE_BREAK)) breaks else breaks + 1
  }

  fun looksLikePdf(name: String, head: ByteArray): Boolean =
    name.substringAfterLast('.', "").lowercase() == "pdf" && head.size >= 4 && head.copyOfRange(0, 4).contentEquals(HEADER)

  /**
   * Layout noise removed, paragraphs kept.
   *
   * PDF text arrives broken at the width of the page, so a paragraph is a dozen short lines and a
   * word is often split by a hyphen across two of them. Left as is, a search for a phrase inside
   * the document fails on the phrases that happen to straddle a line break.
   */
  fun clean(raw: String): String {
    val normalized = raw.replace("\r\n", "\n").replace(' ', ' ').replace(PAGE_BREAK, '\n')
    val dehyphenated = Regex("(\\p{L})-\\n(\\p{L})").replace(normalized) { m -> m.groupValues[1] + m.groupValues[2] }
    return dehyphenated
      .lineSequence()
      .map { it.trim() }
      .joinToString("\n")
      // Three or more blank lines carry no meaning that two do not.
      .replace(Regex("\n{3,}"), "\n\n")
      .trim()
  }

  /** True when the document has no usable text layer — a scan, or an export of images. */
  fun looksScanned(text: String, pages: Int): Boolean {
    if (pages <= 0) return text.isBlank()
    return text.length < pages * MIN_CHARS_PER_PAGE
  }

  data class Trimmed(val text: String, val droppedChars: Int) {
    val wasTrimmed: Boolean get() = droppedChars > 0
  }

  /**
   * Cuts to the budget and reports how much was dropped.
   *
   * Reported rather than silently cut: an answer built from the first third of a document is a
   * wrong answer the person has no way of suspecting.
   */
  fun trim(text: String, maxChars: Int = MAX_CHARS): Trimmed =
    if (text.length <= maxChars) Trimmed(text, 0)
    else Trimmed(text.take(maxChars), text.length - maxChars)
}
