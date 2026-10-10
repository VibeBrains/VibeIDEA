// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t

/**
 * The pinned project commands on the number keys.
 *
 * A command list one has to open a menu for is a command list used twice: the value of «run the
 * gates» is that it costs no attention, and a menu costs attention. The nine pinned commands get
 * Ctrl+Shift+Alt+1..9, in the order the project declared them.
 *
 * The binding is by POSITION rather than by id: a project may rename its commands, and a shortcut
 * that silently starts running something else after a rename is worse than no shortcut. Position is
 * the one the Commands menu shows: `order` first, then the name.
 */
abstract class VibePinnedCommandAction(private val index: Int) : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val parsed = VibeProjectCommandsAction.loadOrExplain(project) ?: return
    val pinned = parsed.commands.filter { it.pinned }
    val command = pinned.getOrNull(index - 1) ?: run {
      // Says which slot is empty rather than doing nothing: a shortcut that answers with silence
      // is one people press twice and then stop pressing.
      Messages.showInfoMessage(project, t("commands.noPinned", "index" to index, "count" to pinned.size),
                               t("commands.title"))
      return
    }
    ProjectCommandRunner.getInstance(project).run(command)
  }

  override fun update(e: AnActionEvent) {
    e.presentation.text = t("commands.pinned.title", "index" to index)
  }
}

class VibePinnedCommand1 : VibePinnedCommandAction(1)
class VibePinnedCommand2 : VibePinnedCommandAction(2)
class VibePinnedCommand3 : VibePinnedCommandAction(3)
class VibePinnedCommand4 : VibePinnedCommandAction(4)
class VibePinnedCommand5 : VibePinnedCommandAction(5)
class VibePinnedCommand6 : VibePinnedCommandAction(6)
class VibePinnedCommand7 : VibePinnedCommandAction(7)
class VibePinnedCommand8 : VibePinnedCommandAction(8)
class VibePinnedCommand9 : VibePinnedCommandAction(9)
