// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

/**
 * What the empty canvas of the project picture says, and why it is empty
 *
 * A blank rectangle means four different things: the graph is still being read, it is being built right now
 * It was never built, or it was built and the project has no links between files
 * Each one is a different next step for a person, and none may look like another
 */
object ProjectGraphState {
  enum class Empty {
    /** There is something to draw */
    NONE,

    /** The file has not been read yet */
    LOADING,

    /** A build is running and has not yet counted what it will parse */
    BUILDING,

    /** A build is running and knows how much it parses */
    BUILDING_COUNTED,

    /** No graph file, and nothing is building it */
    NO_GRAPH,

    /** The graph exists and holds files, but no file links to another */
    NO_LINKS,
  }

  fun emptyState(loaded: Boolean, hasGraph: Boolean, hasSubsystems: Boolean, building: Boolean, counted: Boolean): Empty = when {
    !hasGraph && building -> if (counted) Empty.BUILDING_COUNTED else Empty.BUILDING
    !hasGraph && !loaded -> Empty.LOADING
    !hasGraph -> Empty.NO_GRAPH
    !hasSubsystems -> Empty.NO_LINKS
    else -> Empty.NONE
  }
}
