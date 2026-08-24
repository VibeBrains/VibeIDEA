// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryScopeTest {
  private companion object {
    const val WS_A = "/work/projectA"
    const val WS_B = "/work/projectB"
    const val CAP = 500
  }

  /** Deterministic injected clock: each now() call is one second later. */
  private var clock: Instant = Instant.parse("2026-08-24T10:00:00Z")
  private val store = HistoryStore(now = { clock.toString().also { clock = clock.plusSeconds(1) } })

  private fun msg(text: String = "hi", role: Role = Role.USER, images: List<StoredImage> = emptyList(), wireText: String? = null) =
    ChatMessageRecord(role = role, text = text, images = images, at = clock.toString(), wireText = wireText)

  @Test
  fun `matchesWorkspace accepts own and untagged, rejects foreign`() {
    val own = store.create(WS_A, "A")
    val foreign = store.create(WS_B, "B")
    val untagged = store.create(null, null)

    assertTrue(store.matchesWorkspace(own, WS_A))
    assertFalse(store.matchesWorkspace(foreign, WS_A))
    assertTrue(store.matchesWorkspace(untagged, WS_A))
    assertTrue(store.matchesWorkspace(untagged, null))
  }

  @Test
  fun `otherProjectsCount counts foreign tagged threads only, untagged excluded`() {
    store.create(WS_A, "A")
    store.create(WS_B, "B")
    store.create(null, null)

    assertEquals(1, store.otherProjectsCount(WS_A))
    // No current workspace: every tagged thread is foreign, untagged still excluded.
    assertEquals(2, store.otherProjectsCount(null))
  }

  @Test
  fun `listed hides empty threads and sorts by lastModified desc`() {
    val older = store.create(WS_A, "A")
    store.append(older.id, msg("первый"), CAP)
    val empty = store.create(WS_A, "A")
    val newer = store.create(WS_A, "A")
    store.append(newer.id, msg("второй"), CAP)

    val listed = store.listed()
    assertEquals(listOf(newer.id, older.id), listed.map { it.id })

    // all() still shows the empty thread.
    assertEquals(3, store.all().size)
    assertTrue(store.all().any { it.id == empty.id })
  }

  @Test
  fun `duplicate keeps lastModified, gets new id and clones deeply`() {
    val original = store.create(WS_A, "A")
    store.append(original.id, msg("с картинкой", images = listOf(StoredImage("s.png", "image/png", "QUJD")), wireText = "wire"), CAP)
    store.updateState(original.id, ThreadState(targetId = "acp:claude"))
    val source = store.get(original.id)!!

    val copy = store.duplicate(original.id)!!

    assertNotEquals(source.id, copy.id)
    assertEquals(source.lastModified, copy.lastModified)
    assertEquals(source.createdAt, copy.createdAt)
    assertEquals(source.workspaceId, copy.workspaceId)
    assertEquals("acp:claude", copy.state.targetId)
    // Deep clone: same content, distinct instances at every level.
    assertEquals(source.messages.map { it.text }, copy.messages.map { it.text })
    assertNotSame(source.messages, copy.messages)
    assertNotSame(source.messages[0], copy.messages[0])
    assertNotSame(source.messages[0].images[0], copy.messages[0].images[0])
    assertEquals("QUJD", copy.messages[0].images[0].base64)
    assertEquals("wire", copy.messages[0].wireText)
    assertNotSame(source.state, copy.state)
    // Both live in the store.
    assertEquals(2, store.all().size)
  }

  @Test
  fun `claimUntagged restamps only null-workspace threads`() {
    val untagged1 = store.create(null, null)
    val untagged2 = store.create(null, null)
    val tagged = store.create(WS_A, "A")

    val claimed = store.claimUntagged(WS_B, "B")

    assertEquals(2, claimed)
    assertEquals(WS_B, store.get(untagged1.id)!!.workspaceId)
    assertEquals("B", store.get(untagged1.id)!!.workspaceLabel)
    assertEquals(WS_B, store.get(untagged2.id)!!.workspaceId)
    assertEquals(WS_A, store.get(tagged.id)!!.workspaceId)
    // Second claim finds nothing untagged.
    assertEquals(0, store.claimUntagged(WS_B, "B"))
  }

  @Test
  fun `claimUntagged does not touch lastModified`() {
    val untagged = store.create(null, null)
    val stampBefore = store.get(untagged.id)!!.lastModified
    store.claimUntagged(WS_A, "A")
    assertEquals(stampBefore, store.get(untagged.id)!!.lastModified)
  }

  @Test
  fun `reassign restamps without touching lastModified, delete removes`() {
    val thread = store.create(WS_A, "A")
    store.append(thread.id, msg(), CAP)
    val stampBefore = store.get(thread.id)!!.lastModified

    store.reassign(thread.id, WS_B, "B")
    val reassigned = store.get(thread.id)!!
    assertEquals(WS_B, reassigned.workspaceId)
    assertEquals("B", reassigned.workspaceLabel)
    assertEquals(stampBefore, reassigned.lastModified)

    assertTrue(store.delete(thread.id))
    assertNull(store.get(thread.id))
    assertFalse(store.delete(thread.id))
  }
}
