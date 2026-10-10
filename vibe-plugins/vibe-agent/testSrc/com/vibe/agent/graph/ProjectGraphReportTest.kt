// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.CodeGraphIndex.Provenance
import com.vibe.agent.graph.ProjectGraphAnalysis.FileLink
import com.vibe.agent.graph.ProjectGraphReport.Row
import com.vibe.agent.graph.ProjectGraphReport.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectGraphReportTest {
  private fun fact(from: String, to: String) = FileLink(from, to, Provenance.FACT)

  private fun clique(prefix: String, size: Int) =
    (1..size).flatMap { i -> (i + 1..size).map { j -> fact("$prefix/f$i.ts", "$prefix/f$j.ts") } }

  private fun names(prefix: String, size: Int) = (1..size).map { "$prefix/f$it.ts" }

  private val lonely = (1..20).map { "assets/pic$it.png" }
  private val analysis = ProjectGraphAnalysis.analyze(
    names("net", 5) + names("ui", 5) + lonely,
    clique("net", 5) + clique("ui", 5) + fact("net/f1.ts", "ui/f1.ts"),
  )
  private val rows = ProjectGraphReport.rows(analysis)

  @Test
  fun `the report has four sections in a fixed order`() {
    assertEquals(
      listOf(Section.SUBSYSTEMS, Section.HUBS, Section.SURPRISING, Section.ISOLATED),
      rows.filterIsInstance<Row.Heading>().map { it.section })
  }

  @Test
  fun `a heading carries how many entries its section holds in all, not how many are shown`() {
    val isolated = rows.filterIsInstance<Row.Heading>().single { it.section == Section.ISOLATED }
    assertEquals(20, isolated.count)
    assertEquals(ProjectGraphReport.ISOLATED_SHOWN, rows.count { it is Row.IsolatedRow })
    assertEquals(20 - ProjectGraphReport.ISOLATED_SHOWN, rows.filterIsInstance<Row.More>().single().count)
  }

  @Test
  fun `subsystem rows point at the subsystem, hub rows at the file, bridge rows at the importing file`() {
    val subsystem = rows.filterIsInstance<Row.SubsystemRow>().first { it.label == "net" }
    assertEquals(analysis.subsystems.first { it.label == "net" }.id, subsystem.id)
    assertEquals(5, subsystem.files)
    val hub = rows.filterIsInstance<Row.HubRow>().first()
    assertTrue(hub.links >= rows.filterIsInstance<Row.HubRow>().last().links, "the best connected come first")
    assertTrue(hub.subsystem != null && hub.subsystemLabel != null)
    val bridge = rows.filterIsInstance<Row.BridgeRow>().single()
    assertEquals("net/f1.ts", bridge.from)
    assertEquals(1, bridge.bridges)
    assertEquals(setOf("net", "ui"), setOf(bridge.fromLabel, bridge.toLabel))
  }

  @Test
  fun `a short list shows no tail`() {
    val short = ProjectGraphReport.rows(ProjectGraphAnalysis.analyze(listOf("a.ts", "b.ts"), emptyList()))
    assertEquals(2, short.count { it is Row.IsolatedRow })
    assertTrue(short.none { it is Row.More })
  }
}
