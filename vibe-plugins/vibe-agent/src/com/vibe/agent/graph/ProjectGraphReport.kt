// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.ProjectGraphAnalysis.Analysis

/**
 * The report beside the picture as rows, apart from the words they are shown with
 *
 * What a row says and what a click on it does is decided here and tested here
 * The panel only turns a row into text and a colour dot
 */
object ProjectGraphReport {
  /** The lone files shown before «and N more»: the list is a pointer to look, not an inventory */
  const val ISOLATED_SHOWN = 15

  enum class Section { SUBSYSTEMS, HUBS, SURPRISING, ISOLATED }

  sealed interface Row {
    /** A heading of a section; [count] is how many entries it holds in all */
    data class Heading(val section: Section, val count: Int) : Row

    /** Opens the subsystem on the picture */
    data class SubsystemRow(val id: Int, val label: String, val files: Int, val hub: String) : Row

    /** Opens the file */
    data class HubRow(val file: String, val shown: String, val links: Int, val subsystem: Int?, val subsystemLabel: String?) : Row

    /** Opens the importing file; [bridges] of 1 means this is the only link between the two subsystems */
    data class BridgeRow(
      val from: String,
      val to: String,
      val shownFrom: String,
      val shownTo: String,
      val bridges: Int,
      val fromSubsystem: Int,
      val fromLabel: String,
      val toLabel: String,
    ) : Row

    /** Opens the file */
    data class IsolatedRow(val file: String, val shown: String) : Row

    /** The tail the list does not show */
    data class More(val count: Int) : Row
  }

  fun rows(analysis: Analysis): List<Row> {
    val report = analysis.report
    val labelOf = analysis.subsystems.associate { it.id to it.label }
    val rows = ArrayList<Row>()

    rows.add(Row.Heading(Section.SUBSYSTEMS, analysis.subsystems.size))
    for (subsystem in analysis.subsystems) {
      rows.add(Row.SubsystemRow(subsystem.id, subsystem.label, subsystem.files.size, analysis.relative(subsystem.hub)))
    }

    rows.add(Row.Heading(Section.HUBS, report.hubs.size))
    for (hub in report.hubs) {
      rows.add(Row.HubRow(hub.file, analysis.relative(hub.file), hub.degree, hub.subsystem.takeIf { it in labelOf }, labelOf[hub.subsystem]))
    }

    rows.add(Row.Heading(Section.SURPRISING, report.surprising.size))
    for (entry in report.surprising) {
      rows.add(
        Row.BridgeRow(
          from = entry.link.from,
          to = entry.link.to,
          shownFrom = analysis.relative(entry.link.from),
          shownTo = analysis.relative(entry.link.to),
          bridges = entry.bridgeCount,
          fromSubsystem = entry.fromSubsystem,
          fromLabel = labelOf.getValue(entry.fromSubsystem),
          toLabel = labelOf.getValue(entry.toSubsystem),
        )
      )
    }

    rows.add(Row.Heading(Section.ISOLATED, report.isolated.size))
    for (file in report.isolated.take(ISOLATED_SHOWN)) rows.add(Row.IsolatedRow(file, analysis.relative(file)))
    if (report.isolated.size > ISOLATED_SHOWN) rows.add(Row.More(report.isolated.size - ISOLATED_SHOWN))
    return rows
  }
}
