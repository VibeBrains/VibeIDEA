// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.ProjectGraphState.Empty
import kotlin.test.Test
import kotlin.test.assertEquals

/** An empty canvas means four different things, and each says its own */
class ProjectGraphStateTest {
  private fun state(loaded: Boolean = true, graph: Boolean = false, subsystems: Boolean = false, building: Boolean = false, counted: Boolean = false) =
    ProjectGraphState.emptyState(loaded, graph, subsystems, building, counted)

  @Test
  fun `while the graph is being built the canvas says so, and how far it has got once it knows`() {
    assertEquals(Empty.BUILDING, state(building = true))
    assertEquals(Empty.BUILDING_COUNTED, state(building = true, counted = true))
    // Opened in the middle of a build, before the first read finished: the build is what matters
    assertEquals(Empty.BUILDING, state(loaded = false, building = true))
  }

  @Test
  fun `before the first read it says it is reading, after it with nothing found it says there is no graph`() {
    assertEquals(Empty.LOADING, state(loaded = false))
    assertEquals(Empty.NO_GRAPH, state(loaded = true))
  }

  @Test
  fun `a graph without links says there are no subsystems, a graph with them says nothing`() {
    assertEquals(Empty.NO_LINKS, state(graph = true))
    assertEquals(Empty.NONE, state(graph = true, subsystems = true))
    // A rebuild over an existing graph does not blank the picture
    assertEquals(Empty.NONE, state(graph = true, subsystems = true, building = true))
  }
}
