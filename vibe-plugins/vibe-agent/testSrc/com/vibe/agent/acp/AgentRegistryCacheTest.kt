// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A second look at the catalog costs a 304, and no network shows the last catalog with its date. */
class AgentRegistryCacheTest {
  private val cached = AgentRegistryCache.Cached("""{"agents":[]}""", "\"etag-1\"", 1_000L)

  @Test
  fun `a 304 serves the kept catalog`() {
    assertEquals(AgentRegistryCache.Outcome.NotModified(cached), AgentRegistryCache.decide(304, "", "\"etag-1\"", null, cached))
  }

  @Test
  fun `a 200 brings a new catalog with its tag`() {
    assertEquals(AgentRegistryCache.Outcome.Fresh("new", "\"etag-2\""), AgentRegistryCache.decide(200, "new", "\"etag-2\"", null, cached))
  }

  @Test
  fun `no answer or a server error falls back to the kept catalog and says why`() {
    val offline = assertIs<AgentRegistryCache.Outcome.Offline>(AgentRegistryCache.decide(null, null, null, "timeout", cached))
    assertEquals("timeout", offline.reason)
    assertEquals("HTTP 503", assertIs<AgentRegistryCache.Outcome.Offline>(AgentRegistryCache.decide(503, "x", null, null, cached)).reason)
  }

  @Test
  fun `nothing kept and no answer is a failure`() {
    assertIs<AgentRegistryCache.Outcome.Failed>(AgentRegistryCache.decide(null, null, null, "no route", null))
    assertIs<AgentRegistryCache.Outcome.Failed>(AgentRegistryCache.decide(304, null, null, null, null))
  }

  @Test
  fun `the kept catalog survives a restart`(@TempDir dir: Path) {
    val file = dir.resolve("vibe").resolve("acpRegistry.json")
    AgentRegistryCache.save(cached, file)
    assertEquals(cached, AgentRegistryCache.load(file))
    assertNull(AgentRegistryCache.load(dir.resolve("none.json")))
  }
}
