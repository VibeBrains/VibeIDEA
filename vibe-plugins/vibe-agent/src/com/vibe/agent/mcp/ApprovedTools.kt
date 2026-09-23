// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.ide.util.PropertiesComponent
import com.vibe.agent.providers.ToolSpec

/**
 * The tool set of an MCP server that the person accepted, kept across IDE restarts.
 *
 * Stored in settings rather than in memory: a description swap is designed to surface late ([ToolFingerprint]),
 * and a guard that forgets the approval on restart would differ from no guard only on paper.
 *
 * The key includes the working directory: a server with the same name in another project is another server, and
 * consent given in one's own project must not carry over to a foreign clone.
 */
object ApprovedTools {
  /** The first connection needs no consent: there is nothing to approve yet, nobody has seen the set. */
  fun isFirstSight(project: String?, server: String): Boolean = read(project, server) == null

  /** What changed since approval. The drift is empty both when nothing changed and on first sight. */
  fun drift(project: String?, server: String, specs: List<ToolSpec>): ToolFingerprint.Drift {
    val approved = read(project, server) ?: return ToolFingerprint.Drift(emptyList(), emptyList(), emptyList())
    return ToolFingerprint.compare(approved, ToolFingerprint.map(specs))
  }

  /** Remember the current set as approved. */
  fun approve(project: String?, server: String, specs: List<ToolSpec>) {
    val value = ToolFingerprint.map(specs).entries.joinToString(SEPARATOR) { "${it.key}=${it.value}" }
    PropertiesComponent.getInstance().setValue(key(project, server), value)
  }

  private fun read(project: String?, server: String): Map<String, String>? {
    val raw = PropertiesComponent.getInstance().getValue(key(project, server)) ?: return null
    return raw.split(SEPARATOR).mapNotNull { pair ->
      val at = pair.lastIndexOf('=')
      if (at <= 0) null else pair.take(at) to pair.substring(at + 1)
    }.toMap()
  }

  private fun key(project: String?, server: String): String = "$PREFIX${project.orEmpty()}|$server"

  private const val PREFIX = "vibe.mcp.approvedTools."
  /** A newline cannot occur in a tool name, while a comma or a semicolon can. */
  private const val SEPARATOR = "\n"
}
