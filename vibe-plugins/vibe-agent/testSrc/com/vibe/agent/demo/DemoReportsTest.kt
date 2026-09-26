// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.demo

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The demo folders read as the skill writes them: stamp and name, what is inside, newest first */
class DemoReportsTest {
  @Test
  fun `the stamp is split off the name, a folder without one is all name`() {
    assertEquals("2026-09-26T14-05" to "login-flow", DemoReports.parseName("2026-09-26T14-05-login-flow"))
    assertEquals("2026-09-26" to "cart", DemoReports.parseName("2026-09-26-cart"))
    assertEquals(null to "scratch", DemoReports.parseName("scratch"))
  }

  @Test
  fun `folders are listed newest first with what they hold`() {
    val project = Files.createTempDirectory("demos")
    val root = project.resolve(DemoReports.DIR)
    val older = Files.createDirectories(root.resolve("2026-09-25T10-00-old"))
    val newer = Files.createDirectories(root.resolve("2026-09-26T09-30-new"))
    Files.writeString(newer.resolve(DemoReports.REPORT), "<html/>")
    Files.write(newer.resolve(DemoReports.VIDEO), byteArrayOf(0))
    Files.write(newer.resolve("step-01.png"), byteArrayOf(0))
    Files.write(newer.resolve("step-02.png"), byteArrayOf(0))
    Files.writeString(newer.resolve("run.log"), "")
    val reports = DemoReports.list(project)
    assertEquals(listOf("new", "old"), reports.map { it.name })
    assertTrue(reports[0].hasReport && reports[0].hasVideo)
    assertEquals(2, reports[0].shots)
    assertFalse(reports[1].hasReport)
    assertEquals(older, reports[1].dir)
    assertTrue(DemoReports.list(Files.createTempDirectory("none")).isEmpty())
  }
}
