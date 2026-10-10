// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.nio.file.Files
import javax.swing.Icon

/**
 * The Commands button in the window header: the pinned commands of `.vibe/commands.json`, one click each
 *
 * A routine command typed by hand costs attention every time, and a list hidden three menus deep is a list used twice.
 * Pinning, editing and removing live in the same menu, so keeping the list in order never needs the settings.
 *
 * The children are built on every opening: the file changes under git, and a menu that remembers yesterday's list
 * would run yesterday's command.
 */
class VibeCommandsMenuGroup : ActionGroup(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
    e.presentation.text = t("commands.menu")
    e.presentation.description = t("commands.menu.description")
    e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
  }

  override fun getChildren(e: AnActionEvent?): Array<AnAction> {
    val project = e?.project ?: return EMPTY_ARRAY
    val parsed = ProjectCommandRunner.getInstance(project).load() ?: return arrayOf(CreateFileAction())
    val pinned = parsed.commands.filter { it.pinned }
    val children = ArrayList<AnAction>()
    pinned.forEachIndexed { index, command -> children.add(RunAction(command, index + 1)) }
    if (pinned.isEmpty()) children.add(HintAction(if (parsed.commands.isEmpty()) t("commands.menu.empty") else t("commands.menu.noPinned")))
    children.add(Separator.getInstance())
    ActionManager.getInstance().getAction(ALL_COMMANDS_ACTION)?.let { children.add(it) }
    if (parsed.commands.isNotEmpty()) {
      children.add(DefaultActionGroup(t("commands.menu.manage"), true).apply {
        parsed.commands.forEach { command ->
          add(DefaultActionGroup(command.name, true).apply {
            templatePresentation.icon = stripe(command)
            add(PinAction(command))
            add(EditAction(command))
            add(RemoveAction(command))
          })
        }
      })
    }
    children.add(OpenFileAction())
    // What the file gets wrong is said where the person looks, not in a log
    if (parsed.problems.isNotEmpty()) children.add(HintAction(t("commands.menu.problems", "problems" to parsed.problems.joinToString(", "))))
    if (parsed.notes.isNotEmpty()) children.add(HintAction(t("commands.menu.notes", "notes" to parsed.notes.joinToString(", "))))
    return children.toTypedArray()
  }

  /** One pinned command; the first nine carry the number key that runs them */
  private class RunAction(private val command: ProjectCommands.Command, position: Int) :
    DumbAwareAction(command.name, command.description ?: command.line, stripe(command)) {
    init {
      if (position <= PINNED_KEYS) {
        ActionManager.getInstance().getAction(PINNED_ACTION_PREFIX + position)
          ?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }?.takeIf { it.isNotEmpty() }
          ?.let { templatePresentation.putClientProperty(ActionUtil.SECONDARY_TEXT, it) }
      }
    }

    override fun actionPerformed(e: AnActionEvent) {
      e.project?.let { ProjectCommandRunner.getInstance(it).run(command) }
    }
  }

  /** A line that says why the menu is empty; it does nothing and looks like it */
  private class HintAction(text: String) : DumbAwareAction(text) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
    override fun actionPerformed(e: AnActionEvent) = Unit
  }

  private class PinAction(private val command: ProjectCommands.Command) :
    DumbAwareAction(if (command.pinned) t("commands.menu.unpin") else t("commands.menu.pin")) {
    override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      rewrite(project, templatePresentation.text) { ProjectCommandsEdit.setPinned(it, command.id, !command.pinned) }
    }
  }

  private class EditAction(private val command: ProjectCommands.Command) : DumbAwareAction(t("commands.menu.edit")) {
    override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      val file = virtualFile(project) ?: return
      val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return
      OpenFileDescriptor(project, file, ProjectCommandsEdit.offsetOf(text, command.id) ?: 0).navigate(true)
    }
  }

  private class RemoveAction(private val command: ProjectCommands.Command) : DumbAwareAction(t("common.delete")) {
    override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      val answer = Messages.showYesNoDialog(project, t("commands.menu.remove.confirm", "name" to command.name, "command" to command.line),
                                            t("commands.title"), t("common.delete"), t("common.cancel"), Messages.getQuestionIcon())
      if (answer == Messages.YES) rewrite(project, templatePresentation.text) { ProjectCommandsEdit.remove(it, command.id) }
    }
  }

  private class OpenFileAction : DumbAwareAction(t("commands.menu.open", "path" to ProjectCommands.FILE)) {
    override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      virtualFile(project)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }
  }

  /** No file yet: write the sample and open it, so the first command is an edit away rather than a manual away */
  private class CreateFileAction : DumbAwareAction(t("commands.menu.create", "path" to ProjectCommands.FILE)) {
    override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      val path = ProjectCommandRunner.getInstance(project).file() ?: return
      val sample = VibeCommandsMenuGroup::class.java.getResourceAsStream(SAMPLE)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
      runCatching {
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) Files.writeString(path, sample)
      }.onFailure {
        Messages.showWarningDialog(project, t("commands.failed", "name" to ProjectCommands.FILE, "reason" to (it.message ?: it.javaClass.simpleName)),
                                   t("commands.title"))
        return
      }
      virtualFile(project)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }
  }

  /** The command's colour as a stripe at the left edge of its row */
  private class StripeIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(ICON_SIZE)
    override fun getIconHeight(): Int = JBUI.scale(ICON_SIZE)
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      g.color = color
      g.fillRoundRect(x + JBUI.scale(STRIPE_INSET), y, JBUI.scale(STRIPE_WIDTH), iconHeight, JBUI.scale(STRIPE_WIDTH), JBUI.scale(STRIPE_WIDTH))
    }
  }

  companion object {
    private const val ALL_COMMANDS_ACTION = "Vibe.ProjectCommands"
    private const val PINNED_ACTION_PREFIX = "Vibe.PinnedCommand"
    private const val PINNED_KEYS = 9
    private const val SAMPLE = "/vibeTemplates/commands.json"
    private const val ICON_SIZE = 16
    private const val STRIPE_WIDTH = 4
    private const val STRIPE_INSET = 2

    private fun virtualFile(project: Project): VirtualFile? =
      ProjectCommandRunner.getInstance(project).file()?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }

    /** One undoable edit of the file's text; nothing is written when the command is no longer in it */
    private fun rewrite(project: Project, title: String, edit: (String) -> String?) {
      val file = virtualFile(project) ?: return
      val documents = FileDocumentManager.getInstance()
      val document = documents.getDocument(file) ?: return
      val edited = edit(document.text) ?: return
      WriteCommandAction.runWriteCommandAction(project, title, null, { document.setText(edited) })
      documents.saveDocument(document)
    }

    private fun stripe(command: ProjectCommands.Command): Icon =
      colorOf(ProjectCommands.colorKey(command.color))?.let { StripeIcon(it) } ?: EmptyIcon.create(JBUI.scale(ICON_SIZE))

    /** Theme tokens, written out literally: a key assembled from parts cannot be found by searching the code */
    private fun colorOf(key: String?): Color? = when (key) {
      "red" -> JBColor.namedColor("Vibe.Commands.red", JBColor.RED)
      "green" -> JBColor.namedColor("Vibe.Commands.green", JBColor.GREEN)
      "yellow" -> JBColor.namedColor("Vibe.Commands.yellow", JBColor.YELLOW)
      "blue" -> JBColor.namedColor("Vibe.Commands.blue", JBColor.BLUE)
      "magenta" -> JBColor.namedColor("Vibe.Commands.magenta", JBColor.MAGENTA)
      "cyan" -> JBColor.namedColor("Vibe.Commands.cyan", JBColor.CYAN)
      "orange" -> JBColor.namedColor("Vibe.Commands.orange", JBColor.ORANGE)
      "purple" -> JBColor.namedColor("Vibe.Commands.purple", JBColor.MAGENTA)
      else -> null
    }
  }
}
