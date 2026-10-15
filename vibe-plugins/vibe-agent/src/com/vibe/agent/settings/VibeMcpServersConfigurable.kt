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
 * MCP servers: the servers of `~/.vibe/mcp.json` and `.vibe/mcp.json` as cards
 * Each card has the switch, the edit and the removal, and a form adds a server
 *
 * The files are the only place the servers live: a click writes at once into the file the card is from (as an edit of the text, so
 * the key order, the layout and the fields this product does not know stay), and nothing waits for Apply
 * What the page says about a server's state is what the chat that runs the servers knows ([VibeMcpServers]);
 * a server that nobody has started in this session is told so rather than guessed
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
      override fun toggle(layer: McpServersFile.Layer, name: String, enabled: Boolean) {
        rewrite(layer, name, ofExisting { McpServersEdit.setDisabled(it, name, !enabled) })
        if (!enabled && note == null) release(name)
      }

      override fun edit(layer: McpServersFile.Layer, name: String?) = closeSettingsThen { file.open(layer, name) }

      override fun delete(layer: McpServersFile.Layer, name: String) {
        val question = t("settings.mcp.deleteConfirm", "name" to name, "file" to layer.fileLabel)
        val answer = Messages.showYesNoDialog(project, question, t("settings.mcp.title"),
                                              t("common.delete"), t("common.cancel"), Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        rewrite(layer, name, ofExisting { McpServersEdit.remove(it, name) })
        if (note == null) release(name)
      }

      override fun restart(name: String) = this@VibeMcpServersConfigurable.restart(name)

      override fun confirmTools() = VibeApproveToolsAction().run(project) { name -> confirmed(name) }

      override fun add(layer: McpServersFile.Layer, entry: McpServersFile.Entry): String? {
        val refusal = file.rewrite(layer, t("settings.mcp.title"), entry.name) { text -> McpServersEdit.add(text, entry) }
        // A refusal is told by the form, under the fields; the page itself changes only when the file did
        refresh()
        return refusal
      }

      override fun taken(layer: McpServersFile.Layer): Set<String> = layered(texts()).names[layer].orEmpty()
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

  /** The text of each file as the page sees it: the editor's where a file is open, the disk's otherwise; null for a missing file */
  private fun texts(): Map<McpServersFile.Layer, String?> = McpServersFile.Layer.entries.associateWith { file.read(it) }

  private fun layered(texts: Map<McpServersFile.Layer, String?>): McpServersFile.Merged = McpServersFile.merge(
    texts[McpServersFile.Layer.USER]?.let { McpServersFile.parse(it) } ?: McpServersFile.EMPTY,
    texts[McpServersFile.Layer.PROJECT]?.let { McpServersFile.parse(it) } ?: McpServersFile.EMPTY,
  )

  /** Read the files and what the chat knows, draw the cards */
  private fun refresh() {
    val page = view ?: return
    val texts = texts()
    val merged = layered(texts)
    val servers = VibeMcpServers.getInstance(project)
    val cards = McpServersPage.cards(merged, servers.report(), servers.canRestart)
    val existing = texts.filterValues { it != null }.keys
    page.render(McpServersPageView.Model(cards, McpServersPage.problems(merged) + listOfNotNull(note), restarting.toSet(), existing))
  }

  /** One edit of a file; what it did not do is said on the page, what it did is drawn from the files again */
  private fun rewrite(layer: McpServersFile.Layer, name: String, edit: (String?) -> McpServersEdit.Outcome) {
    note = file.rewrite(layer, t("settings.mcp.title"), name, edit)
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
