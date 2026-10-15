// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.McpServersEdit
import com.vibe.agent.mcp.McpServersFile
import com.vibe.agent.mcp.VibeApproveToolsAction
import com.vibe.agent.mcp.VibeMcpServers
import java.awt.BorderLayout
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * MCP servers: the servers of `.vibe/mcp.json` as cards, with the switch, the edit, the removal and the form that adds one
 *
 * The file is the only place the servers live: a click writes into it at once (as an edit of the text, so the key order, the
 * layout and the fields this product does not know stay), and nothing waits for Apply. What the page says about a server's state
 * is what the chat that runs the servers knows ([VibeMcpServers]); a server that nobody has started in this session is told so
 * rather than guessed
 */
// NoScroll: the page scrolls itself (see VibeProvidersConfigurable)
class VibeMcpServersConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
  private var view: McpServersPageView? = null
  private var uiDisposable: Disposable? = null
  private val file = McpFileAccess(project)

  /** Why the last edit of the file was refused: stays on the page until an edit succeeds */
  private var note: String? = null

  /** Servers whose restart is under way: it can take as long as the server needs to answer */
  private val restarting = ConcurrentHashMap.newKeySet<String>()

  override fun getDisplayName(): String = t("settings.mcp.title")

  override fun createComponent(): JComponent {
    val page = McpServersPageView(object : McpServersPageView.Actions {
      override fun toggle(name: String, enabled: Boolean) {
        rewrite(name, ofExisting { McpServersEdit.setDisabled(it, name, !enabled) })
        if (!enabled && note == null) release(name)
      }

      override fun edit(name: String?) = closeSettingsThen { file.open(name) }

      override fun delete(name: String) {
        val answer = Messages.showYesNoDialog(project, t("settings.mcp.deleteConfirm", "name" to name), t("settings.mcp.title"),
                                              t("common.delete"), t("common.cancel"), Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        rewrite(name, ofExisting { McpServersEdit.remove(it, name) })
        if (note == null) release(name)
      }

      override fun restart(name: String) = this@VibeMcpServersConfigurable.restart(name)

      override fun confirmTools() = VibeApproveToolsAction().run(project) { name -> confirmed(name) }

      override fun add(entry: McpServersFile.Entry): String? {
        val refusal = file.rewrite(t("settings.mcp.title"), entry.name) { text -> McpServersEdit.add(text, entry) }
        // A refusal is told by the form, under the fields; the page itself changes only when the file did
        refresh()
        return refusal
      }

      override fun taken(): Set<String> = McpServersFile.parse(file.read().orEmpty()).servers.mapTo(HashSet()) { it.name }
    })
    view = page

    val disposable = Disposer.newDisposable("VibeMcpServersConfigurable")
    uiDisposable = disposable
    // The chat starts and loses servers on its own threads; the page follows
    VibeMcpServers.getInstance(project).onChange(disposable) {
      SwingUtilities.invokeLater { if (view === page && !project.isDisposed) refresh() }
    }

    refresh()
    return JPanel(BorderLayout(0, JBUI.scale(PAGE_GAP))).apply {
      border = JBUI.Borders.empty(PAGE_PADDING)
      add(page.header, BorderLayout.NORTH)
      add(SettingsUi.page(page.content), BorderLayout.CENTER)
    }
  }

  /** Read the file and what the chat knows, draw the cards */
  private fun refresh() {
    val page = view ?: return
    val text = file.read()
    val parsed = text?.let { McpServersFile.parse(it) } ?: McpServersFile.EMPTY
    val servers = VibeMcpServers.getInstance(project)
    val cards = McpServersPage.cards(parsed.servers, servers.report(), servers.canRestart)
    page.render(McpServersPageView.Model(cards, McpServersPage.problems(parsed) + listOfNotNull(note), restarting.toSet(), text != null))
  }

  /** One edit of the file; what it did not do is said on the page, what it did is drawn from the file again */
  private fun rewrite(name: String, edit: (String?) -> McpServersEdit.Outcome) {
    note = file.rewrite(t("settings.mcp.title"), name, edit)
    refresh()
  }

  /** An edit of an entry that is in the file: with no file there is no entry to find */
  private fun ofExisting(edit: (String) -> McpServersEdit.Outcome): (String?) -> McpServersEdit.Outcome = { text ->
    if (text == null) McpServersEdit.Outcome.Refused(McpServersEdit.Reason.NO_SUCH_SERVER) else edit(text)
  }

  private fun restart(name: String) {
    val page = view ?: return
    restarting += name
    refresh()
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        VibeMcpServers.getInstance(project).restart(name)
      }
      catch (e: Exception) {
        logger<VibeMcpServersConfigurable>().warn("could not restart the MCP server $name", e)
      }
      finally {
        restarting -= name
      }
      SwingUtilities.invokeLater { if (view === page && !project.isDisposed) refresh() }
    }
  }

  /**
   * The file no longer wants [name] to run: the chat that holds it stops it now, and not at its next turn
   *
   * The restart brings a server in line with the file, and an entry that is off or gone is brought to a stop
   */
  private fun release(name: String) {
    val servers = VibeMcpServers.getInstance(project)
    if (servers.canRestart) ApplicationManager.getApplication().executeOnPooledThread { servers.restart(name) }
  }

  /** The tools of [name] were confirmed in the dialog: the chat that held them back looks again */
  private fun confirmed(name: String) {
    ApplicationManager.getApplication().executeOnPooledThread { VibeMcpServers.getInstance(project).confirmed(name) }
  }

  /**
   * Settings are a modal dialog, and an editor opened behind one cannot be typed into: the dialog closes first
   *
   * Closing applies what other pages have pending, as OK does, so that nothing the person typed is lost by a click on a link
   */
  private fun closeSettingsThen(action: () -> Unit) {
    val dialog = view?.content?.let { DialogWrapper.findInstance(it) }
    if (dialog is SettingsDialog) {
      dialog.applyAndClose(false)
      ApplicationManager.getApplication().invokeLater(action, ModalityState.nonModal())
    }
    else action()
  }

  override fun isModified(): Boolean = false

  override fun apply() {}

  override fun reset() = refresh()

  override fun disposeUIResources() {
    uiDisposable?.let { Disposer.dispose(it) }
    uiDisposable = null
    view = null
    restarting.clear()
  }

  private companion object {
    const val PAGE_PADDING = 8
    const val PAGE_GAP = 6
  }
}
