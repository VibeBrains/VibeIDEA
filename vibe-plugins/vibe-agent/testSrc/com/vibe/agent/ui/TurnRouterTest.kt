// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.pipelines.RolePaths
import java.awt.Component
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every event of a running step is found by the session it came from — the rule that lets steps run at once without
 * one step's write being checked against another's boundary.
 */
class TurnRouterTest {
  private val nowhere = object : TurnFeed {
    override fun addRecord(row: JComponent) {}
    override fun addExtra(row: JComponent, before: Component?) {}
  }

  private fun step(role: String, session: String, vararg paths: String) =
    TurnState(role = role, scope = RolePaths.Scope(paths.toList()), feed = nowhere).apply { sessionId = session }

  private val chat = TurnState(role = null, feed = nowhere)

  @Test
  fun `an update from a session nobody registered belongs to the chat`() {
    val router = TurnRouter(chat)
    assertSame(chat, router.of(null))
    assertSame(chat, router.of("chat-session"))
  }

  @Test
  fun `a step's write is checked against its own boundary, not its neighbour's`() {
    val router = TurnRouter(chat)
    val backend = step("backend-dev", "s-backend", "server/**")
    val frontend = step("frontend-dev", "s-frontend", "web/**")
    router.register(backend)
    router.register(frontend)

    // The same path, two sessions: allowed for the step that owns it, refused for the other.
    assertTrue(RolePaths.mayWrite("web/app.ts", router.of("s-frontend").scope))
    assertFalse(RolePaths.mayWrite("web/app.ts", router.of("s-backend").scope))
    assertEquals("backend-dev", router.of("s-backend").role)
  }

  @Test
  fun `a later step in the chat's own session takes the session over`() {
    val router = TurnRouter(chat)
    val first = step("planner", "chat-session")
    val second = step("backend-dev", "chat-session")
    router.register(first)
    router.register(second)
    assertSame(second, router.of("chat-session"))
  }

  @Test
  fun `during a run a write from a session no step owns is refused`() {
    val router = TurnRouter(TurnState.whileRunning(ConcurrentHashMap.newKeySet(), nowhere))
    router.register(step("backend-dev", "s-backend", "server/**"))
    // The step's own session writes under its boundary; any other session — the chat's included — writes nowhere.
    assertTrue(RolePaths.mayWrite("server/api.ts", router.of("s-backend").scope))
    assertFalse(RolePaths.mayWrite("server/api.ts", router.of("chat-session").scope))
    assertFalse(RolePaths.mayWrite("README.md", router.of(null).scope))
  }

  @Test
  fun `after the run every session belongs to the chat again`() {
    val router = TurnRouter(chat)
    router.register(step("qa", "s-qa"))
    assertEquals(2, router.all().size)
    router.unregisterAll()
    assertSame(chat, router.of("s-qa"))
    assertEquals(listOf(chat), router.all())
  }

  @Test
  fun `each turn keeps its own answer, files and ceilings`() {
    val one = step("backend-dev", "a")
    val two = step("frontend-dev", "b")
    one.answer?.append("api готов")
    one.changedPaths += "server/api.ts"
    one.toolCallCount.incrementAndGet()
    assertEquals("", two.answer.toString())
    assertTrue(two.changedPaths.isEmpty())
    assertEquals(0, two.toolCallCount.get())
    // The chat collects no answer of its own: nothing reads it.
    assertEquals(null, chat.answer)
  }
}
