// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

/**
 * Which files of the project the graph is about
 *
 * The graph reads at most a few thousand files, in the order the project model lists them
 * A dependency folder that is not excluded from the model would fill that allowance with somebody else's code
 * And the picture of the project would be a picture of `node_modules`
 *
 * Decided by a whole segment of the path, never by a substring: `my_node_modules_notes/` is a folder of the project
 */
object CodeGraphScope {
  /** Folders whose files are never the project's own */
  val SKIPPED_FOLDERS: Set<String> = setOf("node_modules", ".git")

  fun isProjectFile(relativePath: String): Boolean = relativePath.split('/').none { it in SKIPPED_FOLDERS }
}
