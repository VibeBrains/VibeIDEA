// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.product

/**
 * The version block of «О программе», the way VibeIDE shows it: the product version first, then
 * what it is built on, then the exact commit.
 *
 * The platform's own dialog says «VibeIDEA 0.3.0» in the header and «Build #VI-263.300» below it,
 * and a person reading that cannot tell which number is the product and which is the base. Two
 * labelled lines answer that without making anyone learn the numbering.
 *
 * Pure: the values arrive as parameters, the rendering is a list of label/value pairs, and both
 * the HTML for the dialog and the plain text for «Копировать» are derived from the same list — so
 * they cannot drift apart.
 */
object VibeAboutInfo {
  data class Line(val label: String, val value: String)

  /**
   * @param productVersion `0.3.0` — the `full=` version of the application info.
   * @param platformLine `2026.3` — the intellij-community line the fork is on (major.minor).
   * @param buildNumber `VI-263.300` — the platform build number the distribution carries.
   * @param revision the git revision the build was made from, or null when the build did not record it.
   */
  fun lines(
    productVersion: String,
    platformLine: String,
    buildNumber: String,
    revision: String?,
    labels: Labels,
  ): List<Line> = buildList {
    add(Line(labels.version, productVersion))
    add(Line(labels.platform, "intellij-community $platformLine, ${labels.build} $buildNumber"))
    // The commit is the only thing that identifies a build exactly; a version can be rebuilt.
    revision?.takeIf { it.isNotBlank() }?.let { add(Line(labels.revision, it.take(REVISION_CHARS))) }
  }

  /** Twelve characters: unique in any repository this size, short enough to read out loud. */
  const val REVISION_CHARS = 12

  /**
   * The author's signature shown under the version block: "made at the author's site", the site as the link.
   *
   * The domain is visible in the text and reads as an address, which is the point: whoever scrolled to the bottom
   * sees where the product comes from and can go there without guessing.
   */
  data class MadeBy(val label: String, val text: String, val url: String)

  fun html(lines: List<Line>, madeBy: MadeBy? = null): String {
    val block = lines.joinToString("<br>") { "<b>${escape(it.label)}:</b> ${escape(it.value)}" }
    if (madeBy == null) return block
    // The dot is decoration for the eye only: a screen reader should not read a bullet as content.
    val footer = "<span aria-hidden=\"true\">·</span> ${escape(madeBy.label)} " +
                 "<a href=\"${escape(madeBy.url)}\" target=\"_blank\" rel=\"noopener\">${escape(madeBy.text)}</a>"
    return "$block<br>$footer"
  }

  fun plain(lines: List<Line>, madeBy: MadeBy? = null): String {
    val block = lines.joinToString("\n") { "${it.label}: ${it.value}" }
    if (madeBy == null) return block
    return "$block\n${madeBy.label} ${madeBy.text}"
  }

  private fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  interface Labels {
    val version: String
    val platform: String
    val build: String
    val revision: String
    val madeBy: String
  }
}
