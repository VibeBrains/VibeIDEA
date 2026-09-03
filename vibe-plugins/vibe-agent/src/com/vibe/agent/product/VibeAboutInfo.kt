// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
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

  fun html(lines: List<Line>): String =
    lines.joinToString("<br>") { "<b>${escape(it.label)}:</b> ${escape(it.value)}" }

  fun plain(lines: List<Line>): String = lines.joinToString("\n") { "${it.label}: ${it.value}" }

  private fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  interface Labels {
    val version: String
    val platform: String
    val build: String
    val revision: String
  }
}
