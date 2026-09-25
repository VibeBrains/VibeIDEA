// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import com.vibe.agent.i18n.VibeI18n.t
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The detector for the repository's own gates — the release notes gate and the docs ratchet — through the same code
 * the IDE runs, not a second copy in a script that would drift from it.
 *
 * `textSlopCli [--overrides FILE] [--count] PATH...` — a path may be a directory, read recursively for prose files.
 * By default every file is checked against the pass rule and the exit code is 1 when any fails. With `--count` the
 * command only counts: the total goes out as `SLOP_FINDINGS=N`, a line the ratchet script reads, and the exit code is 0.
 * A named path or overrides file that is not there, or a file that cannot be read, is exit code 2: a gate handed a
 * wrong path must not pass on «nothing checked, nothing failed».
 * So is a rule that ran out of its time ([SlopBudget]):
 * The count went without it, and a ratchet would read that as progress
 */
object TextSlopCli {
  @JvmStatic
  fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
  }

  internal fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
    var overrides: Path? = null
    var count = false
    val paths = ArrayList<Path>()
    val rest = args.iterator()
    while (rest.hasNext()) {
      when (val arg = rest.next()) {
        OPT_OVERRIDES -> overrides = if (rest.hasNext()) Path.of(rest.next()) else return usage(err)
        OPT_COUNT -> count = true
        else -> paths.add(Path.of(arg))
      }
    }
    if (paths.isEmpty()) return usage(err)
    // The overrides must be a file: a directory would be skipped as «no overrides» and the run would pass on the
    // shared catalogue alone.
    val missing = paths.filterNot { Files.exists(it) } + listOfNotNull(overrides?.takeUnless { Files.isRegularFile(it) })
    if (missing.isNotEmpty()) {
      missing.forEach { err.println(t("slop.cli.missing", "path" to it)) }
      return EXIT_BROKEN
    }
    val catalog = SlopCheck.withOverrides(overrides) { err.println(t("slop.cli.warning", "text" to it)) }
    if (catalog == null) {
      err.println(t("slop.cli.noCatalog", "reason" to SlopCheck.builtInWarnings.joinToString("; ")))
      return EXIT_BROKEN
    }
    val files = paths.flatMap { prose(it) }.distinct().sorted()
    val budget = SlopBudget()
    var failed = 0
    var total = 0
    var unreadable = 0
    for (file in files) {
      val text = runCatching { Files.readString(file) }.getOrElse {
        err.println(t("slop.cli.unreadable", "path" to file, "reason" to (it.message ?: it.javaClass.simpleName)))
        unreadable++
        continue
      }
      val report = TextSlop.analyze(text, catalog, budget)
      total += report.findings.size
      if (count) {
        if (report.findings.isNotEmpty()) out.println("${report.findings.size}\t$file")
        continue
      }
      if (!report.passed) failed++
      if (report.findings.isEmpty() && report.passed) continue
      out.println(file.toString())
      out.println(SlopRender.render(report, SlopLabels).prependIndent("  "))
    }
    // A count that skipped a file or a rule is lower than the truth, and a ratchet would take it as progress.
    budget.skipped.takeIf { it.isNotEmpty() }?.let { err.println(SlopLabels.skipped(it)) }
    if (unreadable > 0 || budget.skipped.isNotEmpty()) return EXIT_BROKEN
    if (count) {
      out.println("$COUNT_LINE$total")
      return EXIT_OK
    }
    out.println(t("slop.cli.summary", "files" to files.size, "failed" to failed))
    return if (failed == 0) EXIT_OK else EXIT_FAILED
  }

  private fun prose(path: Path): List<Path> = when {
    Files.isDirectory(path) -> Files.walk(path).use { stream ->
      stream.filter { Files.isRegularFile(it) && SlopCheck.isProse(it.toString()) }.toList()
    }
    else -> listOf(path)
  }

  private fun usage(err: PrintStream): Int {
    err.println(t("slop.cli.usage"))
    return EXIT_BROKEN
  }

  private const val OPT_OVERRIDES = "--overrides"
  private const val OPT_COUNT = "--count"

  /** The machine-read line of the count mode; not translated, a script matches it. */
  const val COUNT_LINE = "SLOP_FINDINGS="

  private const val EXIT_OK = 0
  private const val EXIT_FAILED = 1
  private const val EXIT_BROKEN = 2
}
