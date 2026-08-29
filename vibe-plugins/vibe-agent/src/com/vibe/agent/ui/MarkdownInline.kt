// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

/**
 * Renders the prose of an agent message the way it was written: bold, italic, inline code, links,
 * headings and lists — as Swing-flavoured HTML.
 *
 * Models answer in Markdown whether or not anyone asked, so plain text shows `**важно**` with the
 * asterisks and a list as a wall of dashes. Rendering it is not decoration: an unrendered list of
 * steps reads as one paragraph, which is exactly the case where a person misses a step.
 *
 * **Escaping happens FIRST, and that ordering is the security of this file.** Model output is
 * untrusted text: an answer containing `<img src=x onerror=…>` must render as those characters, not
 * as markup Swing's HTML view will try to honour. Markdown is applied only to already-escaped text,
 * so nothing a model writes can become a tag.
 */
object MarkdownInline {
  /** Prose longer than this is left as plain text: Swing's HTML view gets slow, and a wall of text gains nothing. */
  const val MAX_HTML_CHARS = 20_000

  fun looksLikeMarkdown(text: String): Boolean =
    MARKERS.any { it.containsMatchIn(text) }

  /** @return HTML body (without <html> wrapper), or null when the text is better left plain. */
  fun toHtml(text: String): String? {
    if (text.length > MAX_HTML_CHARS || !looksLikeMarkdown(text)) return null
    val escaped = escape(text)
    val lines = escaped.split("\n")
    val out = StringBuilder()
    var inList = false
    for (raw in lines) {
      val line = raw.trimEnd()
      val bullet = BULLET.find(line)
      val heading = HEADING.find(line)
      when {
        bullet != null -> {
          if (!inList) { out.append("<ul style='margin:2px 0 2px 14px'>"); inList = true }
          out.append("<li>").append(inline(bullet.groupValues[2])).append("</li>")
        }
        heading != null -> {
          if (inList) { out.append("</ul>"); inList = false }
          // Headings are rendered as bold text rather than <h1>: a chat bubble is not a document,
          // and browser heading sizes tower over the surrounding conversation.
          out.append("<p style='margin:6px 0 2px 0'><b>").append(inline(heading.groupValues[2])).append("</b></p>")
        }
        line.isBlank() -> {
          if (inList) { out.append("</ul>"); inList = false } else out.append("<br>")
        }
        else -> {
          if (inList) { out.append("</ul>"); inList = false }
          out.append(inline(line)).append("<br>")
        }
      }
    }
    if (inList) out.append("</ul>")
    return out.toString()
  }

  /** Inline marks inside one already-escaped line. */
  internal fun inline(text: String): String {
    // Code spans are pulled OUT before emphasis runs and put back after. Replacing them in place
    // would not help: the emphasis pass walks the whole string afterwards and would happily turn
    // `**text**` INSIDE a code span into bold — the one thing code formatting promises not to do.
    val spans = ArrayList<String>()
    var result = CODE.replace(text) { match ->
      spans.add(match.groupValues[1])
      CODE_PLACEHOLDER_OPEN + (spans.size - 1) + CODE_PLACEHOLDER_CLOSE
    }
    result = BOLD.replace(result) { "<b>" + it.groupValues[1] + "</b>" }
    result = ITALIC.replace(result) { it.groupValues[1] + "<i>" + it.groupValues[2] + "</i>" }
    result = LINK.replace(result) { match ->
      val label = match.groupValues[1]
      val href = match.groupValues[2]
      // Only http(s) and file links become clickable: `javascript:` in a model's answer is exactly
      // the thing an HTML view must never be handed.
      if (SAFE_HREF.matches(href)) "<a href='$href'>$label</a>" else label + " (" + href + ")"
    }
    spans.forEachIndexed { index, code ->
      result = result.replace(
        CODE_PLACEHOLDER_OPEN + index + CODE_PLACEHOLDER_CLOSE,
        "<code style='background:#00000018'>" + code + "</code>",
      )
    }
    return result
  }

  internal fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

  private val MARKERS = listOf(
    Regex("\\*\\*[^*\n]+\\*\\*"),
    Regex("(^|\\s)`[^`\n]+`"),
    Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE),
    Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE),
    Regex("\\[[^\\]\n]+]\\([^)\n]+\\)"),
  )
  private val BULLET = Regex("^(\\s{0,3})[-*+]\\s+(.*)$")
  private val HEADING = Regex("^(\\s{0,3}#{1,6})\\s+(.*)$")
  private val CODE = Regex("`([^`\n]+)`")
  private val BOLD = Regex("\\*\\*([^*\n]+)\\*\\*")
  private val ITALIC = Regex("(^|[^*])\\*([^*\n]+)\\*")
  private val LINK = Regex("\\[([^\\]\n]+)]\\(([^)\n\\s]+)\\)")
  private val SAFE_HREF = Regex("(?i)(https?://|file:/|mailto:)[^\\s]+")

  // Escaping already ran, so «<» cannot appear in the text — the placeholder cannot collide with
  // anything a model wrote.
  private const val CODE_PLACEHOLDER_OPEN = "\u0000code"
  private const val CODE_PLACEHOLDER_CLOSE = "\u0000"
}
