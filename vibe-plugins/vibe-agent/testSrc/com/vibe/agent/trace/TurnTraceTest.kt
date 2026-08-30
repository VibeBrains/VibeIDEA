// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnTraceTest {
  private val labels = object : TurnTrace.Labels {
    override fun header(count: Int) = "шагов: $count"
    override fun kind(kind: TurnTrace.Kind) = kind.name.lowercase() + " "
    override val empty = "пусто"
    override val ms = "мс"
    override val failureMark = "✖"
  }

  private fun event(at: Long, kind: TurnTrace.Kind, name: String, ms: Long? = null, ok: Boolean = true) =
    TurnTrace.Event(at, kind, name, ms, ok)

  @Test
  fun `the recorder keeps a bounded tail`() {
    // Конец хода объясняет его исход; начало — редко, поэтому вытесняется старое.
    val recorder = TurnTrace.Recorder(limit = 3)
    repeat(5) { recorder.add(event(it.toLong(), TurnTrace.Kind.TOOL, "read$it")) }
    assertEquals(3, recorder.size())
    assertEquals("read4", recorder.snapshot().last().name)
  }

  @Test
  fun `the summary counts failures separately from steps`() {
    val events = listOf(
      event(0, TurnTrace.Kind.TOOL, "read", 100),
      event(1, TurnTrace.Kind.TOOL, "write", 50, ok = false),
      event(2, TurnTrace.Kind.GATE, "verify", 900),
    )
    val summary = TurnTrace.summarise(events)
    assertEquals(TurnTrace.Kind.GATE, summary.first().kind, "самое долгое — первым")
    val tools = summary.first { it.kind == TurnTrace.Kind.TOOL }
    assertEquals(2, tools.count)
    assertEquals(1, tools.failures)
    assertEquals(150, tools.totalMs)
  }

  @Test
  fun `a step nobody measured is not counted as instant`() {
    // Длительность null — это «не измеряли», и складывать её как ноль значит врать в отчёте.
    val events = listOf(event(0, TurnTrace.Kind.TOOL, "read"), event(1, TurnTrace.Kind.TOOL, "write", 100))
    assertEquals(1, TurnTrace.slowest(events).size)
  }

  @Test
  fun `the slowest steps come first and are capped`() {
    val events = (1..10).map { event(it.toLong(), TurnTrace.Kind.TOOL, "t$it", it * 100L) }
    val slowest = TurnTrace.slowest(events, count = 3)
    assertEquals(listOf("t10", "t9", "t8"), slowest.map { it.name })
  }

  @Test
  fun `work done twice is visible as a repeat`() {
    val events = listOf(
      event(0, TurnTrace.Kind.TOOL, "read Main.kt"),
      event(1, TurnTrace.Kind.TOOL, "read Main.kt"),
      event(2, TurnTrace.Kind.TOOL, "write Main.kt"),
    )
    assertEquals(mapOf("read Main.kt" to 2), TurnTrace.repeated(events))
  }

  @Test
  fun `an empty trace says so instead of rendering a bare header`() {
    assertEquals("пусто", TurnTrace.render(emptyList(), 0, labels))
  }

  @Test
  fun `the render places each step on the turn's clock and marks failures`() {
    val events = listOf(event(1_000, TurnTrace.Kind.TOOL, "read", 250), event(3_500, TurnTrace.Kind.GATE, "verify", 100, ok = false))
    val text = TurnTrace.render(events, startedAtMs = 1_000, labels = labels)
    assertTrue(text.contains("0.0s"))
    assertTrue(text.contains("2.5s"))
    assertTrue(text.contains("✖"))
    assertTrue(text.contains("250 мс"))
  }
}
