// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.resilience

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A stop pressed during a pause ends it at once, not at the next polling step
 *
 * The pause is the one place the chat sits still for seconds
 * And a stop that is honoured only after a quarter of a second per step would look like a button that does not work
 */
class InterruptiblePauseTest {
  @Test
  fun `a pause that nobody interrupts passes in full`() {
    val pause = InterruptiblePause(stepMs = 20)
    val started = System.nanoTime()
    assertTrue(pause.await(120) { false })
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue(elapsedMs >= 100, "waited only $elapsedMs ms of 120")
  }

  @Test
  fun `no delay at all returns at once`() {
    assertTrue(InterruptiblePause().await(0) { false })
  }

  @Test
  fun `a stop that was pressed before the wait started ends it without waiting`() {
    val started = System.nanoTime()
    assertFalse(InterruptiblePause().await(10_000) { true })
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
  }

  @Test
  fun `wake ends a long wait far sooner than its polling step`() {
    // The step is a minute here: only the wake can end the wait in time
    val pause = InterruptiblePause(stepMs = 60_000)
    val stopped = AtomicBoolean(false)
    val waiting = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val result = AtomicBoolean(true)
    val tookMs = AtomicLong()
    val thread = Thread {
      val started = System.nanoTime()
      waiting.countDown()
      result.set(pause.await(30_000) { stopped.get() })
      tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
      finished.countDown()
    }
    thread.isDaemon = true
    thread.start()
    assertTrue(waiting.await(5, TimeUnit.SECONDS))
    Thread.sleep(100)
    stopped.set(true)
    pause.wake()
    assertTrue(finished.await(5, TimeUnit.SECONDS), "the wait did not end after the wake")
    assertFalse(result.get(), "a wait ended by a stop must report it")
    assertTrue(tookMs.get() < 5_000, "took ${tookMs.get()} ms")
  }

  @Test
  fun `a wake without a stop does not end the wait early`() {
    val pause = InterruptiblePause(stepMs = 20)
    val thread = Thread {
      Thread.sleep(30)
      pause.wake()
    }
    thread.isDaemon = true
    val started = System.nanoTime()
    thread.start()
    assertTrue(pause.await(200) { false })
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 180)
  }
}
