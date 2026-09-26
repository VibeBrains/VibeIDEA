// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.demo

import java.nio.file.Files
import java.nio.file.Path

/**
 * The demo reports the `demo` skill leaves in the project: one folder per run, newest first
 *
 * The skill writes `.vibe/local/demos/<date-time>-<name>/` with a self-contained `report.html`, the video and a picture
 * per step; the folder name is the only index there is, so it is read as written and nothing else is assumed
 * Pure apart from reading the folder: the project root in, the reports out
 */
object DemoReports {
  const val DIR = ".vibe/local/demos"
  const val REPORT = "report.html"
  const val VIDEO = "video.webm"

  private const val SHOT_PREFIX = "step-"
  private const val SHOT_SUFFIX = ".png"

  /** A leading date and time, as the skill stamps them; anything after the separating dash is the run's name */
  private val STAMPED = Regex("^(\\d{4}-\\d{2}-\\d{2}(?:[T_ ]?\\d{2}[-:.]?\\d{2}(?:[-:.]?\\d{2})?)?)-(.+)$")

  data class Report(
    val dir: Path,
    val name: String,
    /** The date and time from the folder name; null when the folder does not start with one */
    val stamp: String?,
    val hasReport: Boolean,
    val hasVideo: Boolean,
    val shots: Int,
    val modifiedMs: Long,
  ) {
    val report: Path get() = dir.resolve(REPORT)
  }

  /** The folder name split into its stamp and name; a folder without a stamp is all name */
  fun parseName(folder: String): Pair<String?, String> =
    STAMPED.matchEntire(folder)?.let { it.groupValues[1] to it.groupValues[2] } ?: (null to folder)

  /** Every run folder under [projectRoot], newest first; no folder — no reports, not an error */
  fun list(projectRoot: Path): List<Report> {
    val root = projectRoot.resolve(DIR)
    if (!Files.isDirectory(root)) return emptyList()
    val folders = Files.list(root).use { stream -> stream.filter { Files.isDirectory(it) }.toList() }
    return folders.map { dir ->
      val (stamp, name) = parseName(dir.fileName.toString())
      val shots = Files.list(dir).use { files ->
        files.filter { it.fileName.toString().let { f -> f.startsWith(SHOT_PREFIX) && f.endsWith(SHOT_SUFFIX) } }.count().toInt()
      }
      Report(dir, name, stamp, Files.isRegularFile(dir.resolve(REPORT)), Files.isRegularFile(dir.resolve(VIDEO)), shots,
             Files.getLastModifiedTime(dir).toMillis())
    }.sortedWith(compareByDescending<Report> { it.stamp ?: "" }.thenByDescending { it.modifiedMs })
  }
}
