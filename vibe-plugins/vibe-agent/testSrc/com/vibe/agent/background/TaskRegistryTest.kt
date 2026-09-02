// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.background

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRegistryTest {
  private val start = 1_000_000L

  @Test
  fun `a job you cannot see is a job you cannot stop`() {
    val registry = TaskRegistry()
    var stopped = false
    val task = registry.start("./gradlew build", start) { stopped = true }
    assertEquals("bg-1", task.id)
    assertEquals(listOf(task.id), registry.running().map { it.id })
    assertTrue(registry.stop(task.id))
    assertTrue(stopped)
  }

  @Test
  fun `stopping a job that already finished is a plain no, not a failure`() {
    // «Этой задачи уже нет» and «не смог остановить» are different answers; keeping the stopper
    // around would let a later stop kill a process the OS has given to somebody else.
    val registry = TaskRegistry()
    val task = registry.start("sleep 1", start) { error("должно быть недостижимо") }
    registry.finish(task.id, TaskRegistry.State.DONE, start + 1000)
    assertFalse(registry.stop(task.id))
    assertFalse(registry.get(task.id)!!.running)
  }

  @Test
  fun `finished jobs keep their duration, running ones grow`() {
    val registry = TaskRegistry()
    val running = registry.start("tail -f log", start) {}
    val done = registry.start("ls", start) {}
    registry.finish(done.id, TaskRegistry.State.DONE, start + 3000)
    assertEquals(3000, registry.get(done.id)!!.ageMs(start + 99_000))
    assertEquals(99_000, registry.get(running.id)!!.ageMs(start + 99_000))
  }

  @Test
  fun `stop all reports how many were actually stopped`() {
    val registry = TaskRegistry()
    val a = registry.start("a", start) {}
    val b = registry.start("b", start) {}
    registry.finish(a.id, TaskRegistry.State.DONE, start + 10)
    assertEquals(1, registry.stopAll())
    assertEquals(listOf(b.id), registry.all().filter { it.id == b.id }.map { it.id })
  }

  @Test
  fun `old finished jobs are forgotten, running ones never`() {
    // A list that grows for the life of the IDE stops being a list; forgetting a RUNNING process
    // is how a handle turns back into a rumour.
    val registry = TaskRegistry()
    val old = registry.start("old", start) {}
    val alive = registry.start("alive", start) {}
    registry.finish(old.id, TaskRegistry.State.DONE, start + 1000)
    registry.forgetFinished(start + TaskRegistry.KEEP_FINISHED_MS + 2000)
    assertNull(registry.get(old.id))
    assertTrue(registry.get(alive.id)!!.running)
  }

  @Test
  fun `ids are short and newest comes first`() {
    val registry = TaskRegistry()
    registry.start("first", start) {}
    val second = registry.start("second", start + 5) {}
    assertEquals("bg-2", second.id)
    assertEquals(second.id, registry.all().first().id)
  }
}
