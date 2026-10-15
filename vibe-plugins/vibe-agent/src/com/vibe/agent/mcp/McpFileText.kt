// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The words for what is wrong with `.vibe/mcp.json`, from the code and the entry name the parser gave
 *
 * The parser knows no language ([McpServersFile.Problem]); the chat feed and the settings page both say the same thing from here
 */
object McpFileText {
  fun of(complaint: McpServersFile.Complaint): String {
    val name = complaint.name.orEmpty()
    return when (complaint.problem) {
      McpServersFile.Problem.NOT_JSON -> t("mcp.servers.notJson")
      McpServersFile.Problem.NO_SECTION -> t("mcp.servers.noSection")
      McpServersFile.Problem.NOT_AN_OBJECT -> t("mcp.servers.notAnObject", "name" to name)
      McpServersFile.Problem.NO_COMMAND -> t("mcp.servers.noCommand", "name" to name)
      McpServersFile.Problem.UNREADABLE -> t("mcp.servers.unreadable")
    }
  }
}
