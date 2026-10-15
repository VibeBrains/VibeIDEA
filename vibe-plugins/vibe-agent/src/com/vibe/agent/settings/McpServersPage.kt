// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.ConfiguredServersSource
import com.vibe.agent.mcp.McpFileText
import com.vibe.agent.mcp.McpServerFailure
import com.vibe.agent.mcp.McpServersEdit
import com.vibe.agent.mcp.McpServersFile

/**
 * Pure logic of the MCP servers page: what a card says about a server, and what the files say about themselves
 *
 * No Swing and no I/O in here, so every rule is a unit test and the view only draws the answers
 */
object McpServersPage {
  enum class Status {
    /** `"disabled": true` in the file: the entry stays, the server does not start */
    DISABLED,

    /** Nobody has started it in this session: the direct chat does that on its first turn, an external agent on its own */
    IDLE,

    RUNNING,

    /** The start failed, or the process went away after it started */
    FAILED,

    /** It runs, but changed its tool descriptions after the person agreed to them, and the model does not get the tools */
    WITHHELD,
  }

  data class Card(
    val name: String,
    /** The command and its arguments as one line, for reading: arguments with spaces are quoted */
    val commandLine: String,
    /** Names only: the values may be keys, and a settings page is no place to show them */
    val envNames: List<String>,
    val status: Status,
    val tools: Int,
    /** In words; set for [Status.FAILED] */
    val reason: String?,
    val restartable: Boolean,
    /** The file the entry is written in: every edit of the card goes there */
    val layer: McpServersFile.Layer,
    /** The project's entry stands where the shared file has one of the same name, and wins over it */
    val overrides: Boolean,
    /** The working folder of the server process as the entry says it; null — the project folder */
    val cwd: String?,
  ) {
    val disabled: Boolean get() = status == Status.DISABLED

    /** Held tools are confirmed by the person; for any other state the link would have nothing to confirm */
    val confirmable: Boolean get() = status == Status.WITHHELD
  }

  /**
   * One card per server that runs here, in the order [McpServersFile.merge] gives: the shared file's servers, then the project's
   *
   * [canRestart]: a chat holds the servers, so a restart reaches them; the states of the report are the chat's, which is
   * why a server nobody started in this session is [Status.IDLE] and not «stopped»
   */
  fun cards(merged: McpServersFile.Merged, report: ConfiguredServersSource.Report, canRestart: Boolean): List<Card> =
    merged.servers.map { entry ->
      val live = report.live[entry.name]
      val failure = report.failed[entry.name]
      val (status, reason) = when {
        entry.disabled -> Status.DISABLED to null
        live != null && !live.alive -> Status.FAILED to McpServerFailure.exited()
        live != null && live.withheld -> Status.WITHHELD to null
        live != null -> Status.RUNNING to null
        failure != null -> Status.FAILED to failure
        else -> Status.IDLE to null
      }
      Card(entry.name, commandLine(entry), entry.env.keys.toList(), status, live?.tools ?: 0, reason,
           restartable = canRestart && status in RESTARTABLE,
           layer = merged.sourceOfName.getValue(entry.name), overrides = entry.name in merged.overrides, cwd = entry.cwd)
    }

  /** The states a restart makes sense for: the chat has touched the server, so there is something to stop or to retry */
  private val RESTARTABLE = setOf(Status.RUNNING, Status.FAILED, Status.WITHHELD)

  fun commandLine(entry: McpServersFile.Entry): String =
    (listOf(entry.command) + entry.args.map { quoteIfNeeded(it) }).joinToString(" ")

  private fun quoteIfNeeded(arg: String): String =
    if (arg.isEmpty() || arg.any { it.isWhitespace() || it == '"' || it == '\'' }) "\"" + arg.replace("\"", "\\\"") + "\"" else arg

  fun statusText(card: Card): String = when (card.status) {
    Status.DISABLED -> t("settings.mcp.status.disabled")
    Status.IDLE -> t("settings.mcp.status.idle")
    Status.RUNNING -> t("settings.mcp.status.running", "count" to card.tools)
    Status.FAILED -> t("settings.mcp.status.failed", "reason" to card.reason.orEmpty())
    Status.WITHHELD -> t("settings.mcp.status.withheld")
  }

  /** The line next to the title of the page: how many servers the file has and how many of them run now */
  fun summary(cards: List<Card>): String = t(
    "settings.mcp.summary",
    "total" to cards.size, "running" to cards.count { it.status == Status.RUNNING || it.status == Status.WITHHELD },
  )

  /** What the files say about themselves: one phrase per complaint of the parser, each with its file, as the chat feed says them */
  fun problems(merged: McpServersFile.Merged): List<String> =
    merged.allProblems.map { (layer, complaint) -> McpFileText.line(layer, complaint) }

  /**
   * The line next to the title of a card: which file the server is from, then its state
   * It gives way to the width, and the tooltip has it whole
   */
  fun metaText(card: Card): String = layerTag(card) + TAG_SEPARATOR + statusText(card)

  /** The short word on a card that tells which file the server is from; the project's entry also says it replaces a shared one */
  fun layerTag(card: Card): String {
    val layer = if (card.layer == McpServersFile.Layer.USER) t("settings.mcp.layer.user") else t("settings.mcp.layer.project")
    return if (card.overrides) layer + TAG_SEPARATOR + t("settings.mcp.layer.overrides") else layer
  }

  private const val TAG_SEPARATOR = " · "

  /** Why an edit of the file was not made */
  fun refusal(reason: McpServersEdit.Reason, name: String): String = when (reason) {
    McpServersEdit.Reason.UNREADABLE -> t("settings.mcp.refused.unreadable")
    McpServersEdit.Reason.NO_SUCH_SERVER -> t("settings.mcp.refused.gone", "name" to name)
    McpServersEdit.Reason.NAME_TAKEN -> t("settings.mcp.form.errNameTaken", "name" to name)
  }
}
