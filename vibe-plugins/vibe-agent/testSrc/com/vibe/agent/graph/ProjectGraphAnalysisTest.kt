// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.CodeGraphIndex.Provenance
import com.vibe.agent.graph.ProjectGraphAnalysis.FileLink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectGraphAnalysisTest {
  private fun fact(from: String, to: String) = FileLink(from, to, Provenance.FACT)
  private fun guess(from: String, to: String) = FileLink(from, to, Provenance.GUESS)

  /** The two-folder project of the VibeIDE test: `ui` and `net`, one bridge from net to ui, one lone file */
  private val files = listOf(
    "src/ui/panel.ts", "src/ui/view.ts", "src/ui/theme.ts",
    "src/net/client.ts", "src/net/retry.ts", "src/net/http.ts",
    "src/lonely.ts",
  )
  private val links = listOf(
    fact("src/ui/panel.ts", "src/ui/view.ts"), fact("src/ui/panel.ts", "src/ui/theme.ts"), fact("src/ui/view.ts", "src/ui/theme.ts"),
    fact("src/net/client.ts", "src/net/retry.ts"), fact("src/net/client.ts", "src/net/http.ts"), fact("src/net/retry.ts", "src/net/http.ts"),
    fact("src/net/http.ts", "src/ui/theme.ts"),
  )

  @Test
  fun `subsystems, hubs, the only bridge and the lonely file`() {
    val analysis = ProjectGraphAnalysis.analyze(files, links)
    assertEquals("src", analysis.root)
    assertEquals(
      listOf("net: net/client.ts, net/http.ts, net/retry.ts", "ui: ui/panel.ts, ui/theme.ts, ui/view.ts").sorted(),
      analysis.subsystems.map { s -> s.label + ": " + s.files.map(analysis::relative).sorted().joinToString(", ") }.sorted(),
    )
    assertEquals(listOf("net/http.ts" to 3, "ui/theme.ts" to 3), analysis.report.hubs.take(2).map { analysis.relative(it.file) to it.degree })
    val bridge = analysis.report.surprising.single()
    assertEquals("net/http.ts -> ui/theme.ts", analysis.relative(bridge.link.from) + " -> " + analysis.relative(bridge.link.to))
    assertEquals(1, bridge.bridgeCount)
    assertTrue(bridge.crossesFolders)
    assertEquals(listOf("src/lonely.ts"), analysis.report.isolated)
    assertEquals(7, analysis.report.linkCount)
    assertEquals(7, analysis.report.facts)
    assertEquals(0, analysis.report.guesses)
  }

  @Test
  fun `a subsystem is named by the folder most of it lives in, not by its hub and not dragged up by one stray file`() {
    val stray = ProjectGraphAnalysis.analyze(
      listOf("src/billing/invoice.ts", "src/billing/tax.ts", "src/billing/pay.ts", "src/shared/uri.ts", "tools/other.ts"),
      listOf(
        fact("src/billing/invoice.ts", "src/shared/uri.ts"), fact("src/billing/invoice.ts", "src/billing/tax.ts"),
        fact("src/billing/tax.ts", "src/shared/uri.ts"), fact("src/billing/tax.ts", "src/billing/invoice.ts"),
        fact("src/billing/pay.ts", "src/shared/uri.ts"), fact("src/billing/pay.ts", "src/billing/invoice.ts"),
        fact("src/billing/pay.ts", "src/billing/tax.ts"),
      ),
    )
    assertEquals(listOf("src/billing"), stray.subsystems.map { it.label })
  }

  @Test
  fun `a label is the last two folders, and two subsystems of one folder are told apart by their hubs`() {
    val files = listOf(
      "app/core/net/http/a.ts", "app/core/net/http/b.ts", "app/core/net/http/c.ts",
      "app/core/net/http/d.ts", "app/core/net/http/e.ts", "app/core/net/http/f.ts",
      "z.ts",
    )
    val links = listOf(
      fact("app/core/net/http/a.ts", "app/core/net/http/b.ts"), fact("app/core/net/http/a.ts", "app/core/net/http/c.ts"),
      fact("app/core/net/http/b.ts", "app/core/net/http/c.ts"),
      fact("app/core/net/http/d.ts", "app/core/net/http/e.ts"), fact("app/core/net/http/d.ts", "app/core/net/http/f.ts"),
      fact("app/core/net/http/e.ts", "app/core/net/http/f.ts"),
    )
    val analysis = ProjectGraphAnalysis.analyze(files, links)
    assertEquals(2, analysis.subsystems.size)
    assertEquals(setOf("net/http · a.ts", "net/http · d.ts"), analysis.subsystems.map { it.label }.toSet())
  }

  @Test
  fun `the files of a subsystem are ordered by their links, the most connected first, ties by path`() {
    val hub = "app/hub.ts"
    val leaves = listOf("app/b.ts", "app/a.ts", "app/c.ts")
    val analysis = ProjectGraphAnalysis.analyze(leaves + hub, leaves.map { fact(hub, it) })
    val subsystem = analysis.subsystems.single()
    assertEquals(listOf(hub, "app/a.ts", "app/b.ts", "app/c.ts"), subsystem.files)
    assertEquals(hub, subsystem.hub)
  }

  @Test
  fun `a pair of files is one link however many symbols go through it`() {
    // Three imports of one file by another: one relationship, and the hub is not inflated by it
    val analysis = ProjectGraphAnalysis.analyze(
      listOf("a.ts", "b.ts"),
      listOf(fact("a.ts", "b.ts"), fact("a.ts", "b.ts"), guess("a.ts", "b.ts"), fact("b.ts", "a.ts")),
    )
    assertEquals(2, analysis.links.size, "a→b and b→a are two directed links, the repeats are not")
    assertEquals(mapOf("a.ts" to 1, "b.ts" to 1), analysis.degreeOf)
    assertEquals(Provenance.FACT, analysis.links.first { it.from == "a.ts" }.provenance, "a fact outweighs a guess of the same pair")
  }

  @Test
  fun `a self-import and a link to an unknown file are dropped`() {
    val analysis = ProjectGraphAnalysis.analyze(listOf("a.ts", "b.ts"), listOf(fact("a.ts", "a.ts"), fact("a.ts", "gone.ts")))
    assertTrue(analysis.links.isEmpty())
    assertEquals(listOf("a.ts", "b.ts"), analysis.report.isolated)
  }

  @Test
  fun `two dense groups joined only by guesses stay two subsystems`() {
    val a = listOf("x/a1.ts", "x/a2.ts", "x/a3.ts", "x/a4.ts")
    val b = listOf("y/b1.ts", "y/b2.ts", "y/b3.ts", "y/b4.ts")
    fun clique(group: List<String>) = group.flatMapIndexed { i, from -> group.drop(i + 1).map { fact(from, it) } }
    val glued = listOf(guess("x/a1.ts", "y/b1.ts"), guess("x/a2.ts", "y/b2.ts"), guess("x/a3.ts", "y/b3.ts"))
    val analysis = ProjectGraphAnalysis.analyze(a + b, clique(a) + clique(b) + glued)
    assertEquals(setOf(a.toSet(), b.toSet()), analysis.subsystems.map { it.files.toSet() }.toSet())
    assertEquals(3, analysis.report.guesses)
  }

  @Test
  fun `the same graph gives the same picture whatever order the files and links come in`() {
    val a = (1..12).map { "pkg/alpha/f$it.ts" }
    val b = (1..9).map { "pkg/beta/g$it.ts" }
    val c = (1..7).map { "other/gamma/h$it.ts" }
    fun ring(group: List<String>) = group.indices.flatMap { i ->
      listOf(fact(group[i], group[(i + 1) % group.size]), fact(group[i], group[(i + 3) % group.size]))
    }
    val all = a + b + c + "lonely.ts"
    val every = ring(a) + ring(b) + ring(c) + fact(a[0], b[0]) + fact(b[4], c[2]) + guess(a[5], c[5])
    val reference = ProjectGraphAnalysis.analyze(all, every)
    for (seed in 1..6) {
      val shuffled = ProjectGraphAnalysis.analyze(all.shuffled(Random(seed)), every.shuffled(Random(seed)))
      assertEquals(reference.communityOf, shuffled.communityOf, "communities, seed $seed")
      assertEquals(reference.subsystems, shuffled.subsystems, "subsystems, seed $seed")
      assertEquals(reference.report, shuffled.report, "report, seed $seed")
    }
  }

  @Test
  fun `bridges are one per pair of subsystems and the rarest come first`() {
    // Three subsystems of five files: between alpha and beta a single link, between beta and gamma two
    fun clique(prefix: String) = (1..5).flatMap { i -> (i + 1..5).map { j -> fact("$prefix/$i.ts", "$prefix/$j.ts") } }
    val names = listOf("alpha", "beta", "gamma").flatMap { p -> (1..5).map { "$p/$it.ts" } }
    val links = clique("alpha") + clique("beta") + clique("gamma") + listOf(
      fact("alpha/1.ts", "beta/1.ts"),
      fact("beta/2.ts", "gamma/2.ts"), fact("beta/3.ts", "gamma/3.ts"),
    )
    val analysis = ProjectGraphAnalysis.analyze(names, links)
    val bridges = analysis.report.surprising
    assertEquals(2, bridges.size, "one row per pair of subsystems")
    assertEquals(listOf(1, 2), bridges.map { it.bridgeCount })
    assertEquals(listOf("alpha/1.ts", "beta/2.ts"), bridges.map { it.link.from })
  }

  @Test
  fun `the report keeps ten hubs and ten bridges at most`() {
    val names = (1..40).map { "pkg$it/a.ts" } + (1..40).map { "pkg$it/b.ts" }
    val links = (1..40).map { fact("pkg$it/a.ts", "pkg$it/b.ts") } + (1..39).map { fact("pkg$it/a.ts", "pkg${it + 1}/b.ts") }
    val report = ProjectGraphAnalysis.analyze(names, links).report
    assertTrue(report.hubs.size <= ProjectGraphAnalysis.REPORT_HUBS)
    assertTrue(report.surprising.size <= ProjectGraphAnalysis.REPORT_SURPRISING)
  }

  @Test
  fun `an empty project does not crash the analysis`() {
    val analysis = ProjectGraphAnalysis.analyze(emptyList(), emptyList())
    assertTrue(analysis.subsystems.isEmpty())
    assertEquals(0, analysis.report.fileCount)
    assertEquals("", analysis.root)
  }
}
