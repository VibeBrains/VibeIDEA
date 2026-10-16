// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.McpServersFile
import com.vibe.agent.ui.composer.StatusDot
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Rectangle
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * What the MCP servers page draws: a card per server of the two files (`~/.vibe/mcp.json`, `.vibe/mcp.json`), and the form that adds one
 *
 * The cards are the ones of the Models page ([CardPanel], [FoldPanel]); a card is open the first time it is drawn, so that the command,
 * the file and the state of a server are in front of the person without a click, except a server that is switched off, which is closed
 * The form that adds a server comes after the cards, because that is where a person who has just read the list looks next
 *
 * No platform services in here: the state comes in as a [Model] and every click goes out through [Actions], so the same view
 * is drawn in a unit test and the screenshots are made from it
 */
internal class McpServersPageView(private val actions: Actions) {
  interface Actions {
    /** The switch of a card: write `disabled` into the file of [layer] or take it out */
    fun toggle(layer: McpServersFile.Layer, name: String, enabled: Boolean)

    /**
     * Open the file of [layer], at the entry of [name] when given
     * The settings close first: an editor opened behind the settings window cannot be reached, in either kind of that window
     */
    fun edit(layer: McpServersFile.Layer, name: String?)

    /** Ask, then cut the entry out of the file of [layer] */
    fun delete(layer: McpServersFile.Layer, name: String)

    fun restart(name: String)

    /** The dialog that lists the servers whose tools changed and takes the person's decision */
    fun confirmTools()

    /** Write the new entry into the file of [layer]; null when it is written, the reason in words when it is not */
    fun add(layer: McpServersFile.Layer, entry: McpServersFile.Entry): String?

    /** Names the file of [layer] has, so that the form refuses a second server under one of them */
    fun taken(layer: McpServersFile.Layer): Set<String>
  }

  /**
   * Everything a redraw needs
   *
   * [warnings] are what the files say wrong about themselves and why the last edit was refused; [restarting] are the servers
   * whose restart is under way; [existing] are the files that are there: a file that is not has no link to open
   */
  data class Model(
    val cards: List<McpServersPage.Card>,
    val warnings: List<String>,
    val restarting: Set<String>,
    val existing: Set<McpServersFile.Layer>,
  )

  private val folds = LinkedHashMap<String, FoldPanel>()
  private var formOpen = false
  private var latest: Model? = null

  private val summary = TruncatingLabel()
  private val openProject = SettingsLink(t("settings.mcp.openProject")) { actions.edit(McpServersFile.Layer.PROJECT, null) }
    .apply { toolTipText = t("settings.mcp.openProjectTooltip") }
  private val openUser = SettingsLink(t("settings.mcp.openUser")) { actions.edit(McpServersFile.Layer.USER, null) }
    .apply { toolTipText = t("settings.mcp.openUserTooltip") }

  private val form = McpServerFormPanel(
    taken = { layer -> actions.taken(layer) },
    submit = { layer, entry -> actions.add(layer, entry).also { if (it == null) hideForm() } },
    cancel = { hideForm() },
  )

  /** The form in the card it is drawn in: the card is what the page scrolls to */
  private val formCard = CardPanel(dashed = true).apply { add(form, BorderLayout.CENTER) }

  /** The title line, the summary and the link to the form: stays on top while the cards scroll, so it must be able to shrink */
  val header: JComponent = JPanel(BorderLayout(JBUI.scale(HEADER_GAP), 0)).apply {
    isOpaque = false
    add(JBLabel(t("settings.mcp.title")).apply { font = JBFont.h3() }, BorderLayout.WEST)
    add(summary, BorderLayout.CENTER)
    add(SettingsLink(t("settings.mcp.add")) { showForm() }, BorderLayout.EAST)
  }

  /** The cards, to be wrapped by the settings page */
  val content: JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
  }

  /** The panel of the form, for a test to type into */
  internal val formPanel: McpServerFormPanel get() = form

  internal val formShown: Boolean get() = formOpen

  /**
   * Draws the page for [model]; the same model as the one on the screen draws nothing
   *
   * The chat reports a change of state on its own threads, and a redraw takes the focus out of the form the person is typing in
   */
  fun render(model: Model, force: Boolean = false) {
    if (!force && model == latest) return
    latest = model
    val wasOpen = folds.filterValues { it.expanded }.keys.toSet()
    val known = folds.keys.toSet()
    folds.clear()
    content.removeAll()
    content.add(SettingsUi.hint(t("settings.mcp.intro")).left())
    for (warning in model.warnings) {
      content.add(SettingsUi.hint(StringUtil.escapeXmlEntities(warning)).left().apply { foreground = StatusDot.WAITING_COLOR })
    }
    if (McpServersFile.Layer.PROJECT in model.existing) content.add(openProject.left())
    if (McpServersFile.Layer.USER in model.existing) content.add(openUser.left())
    content.add(gap())
    if (model.cards.isEmpty() && !formOpen) content.add(emptyState())
    for (card in model.cards) {
      val fold = foldOf(card, card.name in model.restarting)
      // A card the person has seen keeps its open state; one drawn for the first time is open unless the server is off
      fold.expanded = if (card.name in known) card.name in wasOpen else card.status !in CLOSED_BY_DEFAULT
      folds[card.name] = fold
      content.add(CardPanel(dashed = false).apply { add(fold, BorderLayout.CENTER) }.left())
      content.add(gap())
    }
    if (formOpen) {
      content.add(formCard.left())
      content.add(gap())
    }
    summary.text = McpServersPage.summary(model.cards)
    content.revalidate()
    content.repaint()
  }

  /** The cards of the page in the order they are drawn: what a test reads */
  internal fun cardFolds(): List<FoldPanel> = folds.values.toList()

  fun showForm() {
    formOpen = true
    latest?.let { render(it, force = true) }
    form.focusFirst()
    // The form is the last thing on the page and may be under the edge of the window: it is brought into view once laid out
    SwingUtilities.invokeLater { revealForm() }
  }

  /** Scrolls the page to the form, its top first: the title and the cancel link are there */
  internal fun revealForm() {
    if (formOpen) formCard.scrollRectToVisible(Rectangle(0, 0, formCard.width, formCard.height))
  }

  fun hideForm() {
    formOpen = false
    form.clear()
    latest?.let { render(it, force = true) }
  }

  /** One line of explanation and the way to the form: a page without servers is not a dead end */
  private fun emptyState(): JComponent = column().apply {
    add(SettingsUi.hint(t("settings.mcp.empty")).left())
    add(SettingsLink(t("settings.mcp.add")) { showForm() }.left())
  }.left()

  private fun foldOf(card: McpServersPage.Card, restarting: Boolean): FoldPanel {
    val enabled = ModelCheckBox(t("settings.mcp.enabled"), !card.disabled).apply {
      toolTipText = t("settings.mcp.enabledTooltip")
    }
    enabled.addActionListener { actions.toggle(card.layer, card.name, enabled.isSelected) }
    val line = FoldHeader(enabled)
    line.setTitle(card.name)
    line.meta.text = McpServersPage.metaText(card)
    line.meta.foreground = colorOf(card.status)
    return FoldPanel(line, bodyOf(card, restarting))
  }

  private fun bodyOf(card: McpServersPage.Card, restarting: Boolean): JComponent = column().apply {
    border = JBUI.Borders.empty(BODY_TOP_GAP, BODY_INDENT, 0, 0)
    add(SettingsUi.hint(t("settings.mcp.detail.file", "file" to StringUtil.escapeXmlEntities(card.layer.fileLabel))).left())
    if (card.overrides) {
      add(SettingsUi.hint(t("settings.mcp.detail.overrides", "name" to StringUtil.escapeXmlEntities(card.name))).left())
    }
    add(SettingsUi.hint(t("settings.mcp.detail.command", "command" to StringUtil.escapeXmlEntities(card.commandLine))).left())
    card.cwd?.let { add(SettingsUi.hint(t("settings.mcp.detail.cwd", "cwd" to StringUtil.escapeXmlEntities(it))).left()) }
    if (card.envNames.isNotEmpty()) {
      add(SettingsUi.hint(t("settings.mcp.detail.env", "names" to StringUtil.escapeXmlEntities(card.envNames.joinToString(", ")))).left())
    }
    when (card.status) {
      McpServersPage.Status.FAILED ->
        add(SettingsUi.hint(t("settings.mcp.detail.failed", "reason" to StringUtil.escapeXmlEntities(card.reason.orEmpty()))).left())
      McpServersPage.Status.WITHHELD -> add(SettingsUi.hint(t("settings.mcp.detail.withheld")).left())
      McpServersPage.Status.IDLE -> add(SettingsUi.hint(t("settings.mcp.detail.idle")).left())
      McpServersPage.Status.RUNNING, McpServersPage.Status.DISABLED -> Unit
    }
    add(Box.createVerticalStrut(JBUI.scale(BODY_TOP_GAP)))
    add(SettingsLink(t("settings.mcp.edit")) { actions.edit(card.layer, card.name) }
          .apply { toolTipText = t("settings.mcp.editTooltip", "name" to card.name, "file" to card.layer.fileLabel) }.left())
    if (card.restartable) {
      add(SettingsLink(if (restarting) t("settings.mcp.restarting") else t("settings.mcp.restart")) { actions.restart(card.name) }
            .apply {
              // A disabled link hides itself by default, and the word «restarting» is the point of this one
              autoHideOnDisable = false
              isEnabled = !restarting
              toolTipText = t("settings.mcp.restartTooltip")
            }.left())
    }
    if (card.confirmable) {
      add(SettingsLink(t("settings.mcp.confirm")) { actions.confirmTools() }
            .apply { toolTipText = t("settings.mcp.confirmTooltip") }.left())
    }
    add(SettingsLink(t("common.delete")) { actions.delete(card.layer, card.name) }.left())
  }

  private fun colorOf(status: McpServersPage.Status): JBColor = when (status) {
    McpServersPage.Status.RUNNING -> StatusDot.READY_COLOR
    McpServersPage.Status.FAILED -> StatusDot.ERROR_COLOR
    McpServersPage.Status.WITHHELD -> StatusDot.WAITING_COLOR
    McpServersPage.Status.DISABLED, McpServersPage.Status.IDLE -> JBColor.GRAY
  }

  private fun column(): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
  }

  private fun gap(): Component = Box.createVerticalStrut(JBUI.scale(CARD_GAP))

  private fun <T : JComponent> T.left(): T = apply { alignmentX = Component.LEFT_ALIGNMENT }

  private companion object {
    const val HEADER_GAP = 8
    const val CARD_GAP = 8
    const val BODY_INDENT = 18
    const val BODY_TOP_GAP = 6

    /** A server that is switched off has nothing to read: its card is closed when first drawn */
    val CLOSED_BY_DEFAULT = setOf(McpServersPage.Status.DISABLED)
  }
}
