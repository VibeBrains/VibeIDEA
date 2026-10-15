// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * What the Models page draws: a card per provider that has a key, one dashed card for those that wait for one
 *
 * Every block is collapsed when the page is drawn, and nothing remembers it opened: the page is a catalog to dip into,
 * and a row of closed cards is easier to take in than the last state of whatever was opened a week ago
 *
 * No platform services in here: the stored visibility comes in as [isHidden] and the three actions go out through [Actions],
 * so the same view is drawn in a unit test and the screenshots are made from it
 */
internal class ModelsPageView(
  private val isHidden: (providerId: String, modelId: String, defaultHidden: Boolean) -> Boolean,
  private val actions: Actions,
) {
  interface Actions {
    /** Re-read the registry and ask every provider that has a key for its catalog */
    fun refreshAll()

    /** Ask one provider for its catalog; a person's own request, which may read the key itself */
    fun refreshOne(providerId: String)

    /** Open the Providers page, where keys are entered */
    fun enterKey()
  }

  /** A model the provider file declares: on the page from the start, unlike the ones only a catalog brings */
  class Declared(val id: String, val label: String)

  /** A provider as the page draws it */
  class ProviderBlock(val id: String, val name: String, val models: List<Declared>)

  class Row(val providerId: String, val modelId: String, val hay: String, val defaultHidden: Boolean, val box: JBCheckBox)

  private class Block(val id: String, val waiting: Boolean, val fold: FoldPanel, val rowsPanel: JPanel) {
    val rows = ArrayList<Row>()
    var name = ""
    var search: SearchTextField? = null
    var onlyEnabled: JBCheckBox? = null
    var allHidden: JComponent? = null
  }

  /** What a person typed into the filter of a block: kept across a redraw, like the open state */
  private class Filter(val onlyEnabled: Boolean, val search: String)

  private val blocks = LinkedHashMap<String, Block>()
  private var waitingFold: FoldPanel? = null

  private val summary = TruncatingLabel()

  /** The title line, the summary and the refresh link: stays on top while the cards scroll */
  val header: JComponent = JPanel(BorderLayout(JBUI.scale(HEADER_GAP), 0)).apply {
    isOpaque = false
    add(JBLabel(t("settings.models.title")).apply { font = JBFont.h3() }, BorderLayout.WEST)
    add(summary, BorderLayout.CENTER)
    add(SettingsLink(t("settings.models.refreshAll")) { actions.refreshAll() }
          .apply { toolTipText = t("settings.models.refreshAllTooltip") }, BorderLayout.EAST)
  }

  /** The cards, to be wrapped by the settings page */
  val content: JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
  }

  /** Every row of every block, in the order they were added: what the page reads on Apply */
  val rows: List<Row> get() = blocks.values.flatMap { it.rows }

  /** False until the first [render]: before it the page has nothing to show but a note that it is reading */
  var rendered: Boolean = false
    private set

  /** A note in place of the cards: while the registry is being read, or when it could not be */
  fun showNote(text: String) {
    content.removeAll()
    content.add(SettingsUi.hint(text).left())
    content.revalidate()
    content.repaint()
  }

  /**
   * Draw the blocks anew, keeping what a person has done on the page and not yet applied
   *
   * Switches not applied yet, the catalog rows already fetched, the blocks left open and what is typed into their filters survive
   * A refresh must not take away the clicks a person has made or close what they are reading
   */
  fun render(keyed: List<ProviderBlock>, waiting: List<ProviderBlock>) {
    val pending = rows.associate { (it.providerId to it.modelId) to it.box.isSelected }
    val carried = blocks.values.filter { !it.waiting }.flatMap { b -> b.rows.filter { it.defaultHidden }.map { b.id to it.modelId } }
    val open = blocks.values.filter { it.fold.expanded }.mapTo(HashSet()) { it.id }
    val waitingWasOpen = waitingFold?.expanded == true
    val filters = blocks.values.filter { !it.waiting }
      .associate { it.id to Filter(it.onlyEnabled?.isSelected != false, it.search?.text.orEmpty()) }
    rendered = true
    blocks.clear()
    waitingFold = null
    content.removeAll()

    if (keyed.isEmpty() && waiting.isEmpty()) content.add(SettingsUi.hint(t("settings.providers.empty")).left())
    for (p in keyed) {
      val block = keyedBlock(p, pending)
      // Catalog rows known before the redraw come back at once: the answer that follows only adds to them
      for ((providerId, modelId) in carried) {
        if (providerId == p.id && block.rows.none { it.modelId == modelId }) addRow(block, modelId, modelId, defaultHidden = true, pending)
      }
      block.fold.expanded = p.id in open
      filters[p.id]?.let {
        block.onlyEnabled?.isSelected = it.onlyEnabled
        block.search?.text = it.search
      }
      blocks[p.id] = block
      refresh(block)
      content.add(CardPanel(dashed = false).apply { add(block.fold, BorderLayout.CENTER) }.left())
      content.add(gap())
    }
    if (waiting.isNotEmpty()) content.add(waitingCard(waiting, pending, open, waitingWasOpen))
    updateSummary()
    content.revalidate()
    content.repaint()
  }

  /** Put the models a catalog brought that the block does not have yet; returns how many were new */
  fun addCatalog(providerId: String, ids: List<String>): Int {
    val block = blocks[providerId]?.takeIf { !it.waiting } ?: return 0
    val known = block.rows.mapTo(HashSet()) { it.modelId }
    var added = 0
    for (id in ids) {
      if (!known.add(id)) continue
      addRow(block, id, id, defaultHidden = true)
      added++
    }
    refresh(block)
    updateSummary()
    return added
  }

  fun setSource(providerId: String, source: ModelsPage.Source) {
    val block = blocks[providerId] ?: return
    block.fold.header.meta.text = ModelsPage.sourceText(source)
    block.fold.header.revalidate()
  }

  private fun keyedBlock(p: ProviderBlock, pending: Map<Pair<String, String>, Boolean>): Block {
    val rowsPanel = column()
    val search = SearchTextField(false)
    search.textEditor.emptyText.text = t("settings.models.search")
    val onlyEnabled = ModelCheckBox(t("settings.models.onlyEnabled"), true).apply {
      toolTipText = t("settings.models.onlyEnabledTooltip")
    }
    val allHidden = SettingsUi.hint(t("settings.models.allHidden")).left().apply { isVisible = false }
    val controls = JPanel(BorderLayout(JBUI.scale(HEADER_GAP), 0)).apply {
      isOpaque = false
      add(search, BorderLayout.CENTER)
      add(onlyEnabled, BorderLayout.EAST)
    }
    val body = column().apply {
      border = JBUI.Borders.empty(BODY_TOP_GAP, BODY_INDENT, 0, 0)
      add(controls.left())
      add(allHidden)
      add(rowsPanel)
    }
    val refreshOne = SettingsLink(t("settings.models.refreshOne")) { actions.refreshOne(p.id) }
      .apply { toolTipText = t("settings.models.refreshOneTooltip", "provider" to p.name) }
    val block = Block(p.id, waiting = false, FoldPanel(FoldHeader(refreshOne), body), rowsPanel)
    block.name = p.name
    block.search = search
    block.onlyEnabled = onlyEnabled
    block.allHidden = allHidden
    search.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = refresh(block)
      override fun removeUpdate(e: DocumentEvent) = refresh(block)
      override fun changedUpdate(e: DocumentEvent) = refresh(block)
    })
    onlyEnabled.addActionListener { refresh(block) }
    // Hand-declared models are on by default; the ones that only a catalog brings (added later) are off until a person turns them on
    for (m in p.models) addRow(block, m.id, m.label, defaultHidden = false, pending)
    return block
  }

  private fun waitingCard(
    waiting: List<ProviderBlock>,
    pending: Map<Pair<String, String>, Boolean>,
    open: Set<String>,
    wasOpen: Boolean,
  ): JComponent {
    val body = column().apply { border = JBUI.Borders.empty(BODY_TOP_GAP, BODY_INDENT, 0, 0) }
    body.add(SettingsUi.hint(t("settings.models.waiting.explain")).left())
    body.add(SettingsLink(t("settings.models.waiting.enterKey")) { actions.enterKey() }.left())
    body.add(Box.createVerticalStrut(JBUI.scale(BODY_TOP_GAP)))
    for (p in waiting) {
      val rowsPanel = column().apply { border = JBUI.Borders.emptyLeft(BODY_INDENT) }
      val enterKey = SettingsLink(t("settings.models.enterKey")) { actions.enterKey() }
      val fold = FoldPanel(FoldHeader(enterKey), rowsPanel)
      val block = Block(p.id, waiting = true, fold, rowsPanel)
      block.name = p.name
      for (m in p.models) addRow(block, m.id, m.label, defaultHidden = false, pending)
      fold.expanded = p.id in open
      blocks[p.id] = block
      refresh(block)
      body.add(fold.left())
    }
    val header = FoldHeader(action = null)
    header.setTitle("${t("settings.models.waiting.title")}  ${ModelRows.counter(waiting.size, waiting.size)}")
    header.meta.text = ModelsPage.leadNames(waiting.map { it.name })
    val fold = FoldPanel(header, body)
    fold.expanded = wasOpen
    waitingFold = fold
    return CardPanel(dashed = true).apply { add(fold, BorderLayout.CENTER) }.left()
  }

  private fun addRow(
    block: Block,
    modelId: String,
    label: String,
    defaultHidden: Boolean,
    pending: Map<Pair<String, String>, Boolean> = emptyMap(),
  ) {
    val selected = pending[block.id to modelId] ?: !isHidden(block.id, modelId, defaultHidden)
    val box = ModelCheckBox(label, selected).apply { alignmentX = Component.LEFT_ALIGNMENT }
    box.toolTipText = tipOf(box)
    box.addActionListener {
      box.toolTipText = tipOf(box)
      refresh(block)
      updateSummary()
    }
    block.rows.add(Row(block.id, modelId, label, defaultHidden, box))
    block.rowsPanel.add(box)
  }

  private fun tipOf(box: JBCheckBox): String = if (box.isSelected) t("settings.models.show") else t("settings.models.hidden")

  /** Recompute which rows of a block show, its counter, and the line that explains an empty list */
  private fun refresh(block: Block) {
    val tokens = ModelRows.tokens(block.search?.text.orEmpty())
    val onlyEnabled = block.onlyEnabled?.isSelected == true
    var shown = 0
    var afterFilter = 0
    for (r in block.rows) {
      val ok = ModelRows.visible(r.hay, r.box.isSelected, tokens, onlyEnabled)
      r.box.isVisible = ok
      if (ok) shown++
      if (!onlyEnabled || r.box.isSelected) afterFilter++
    }
    // The denominator is every model of the provider and not what is left after the filter: "3 of 3" over a hundred models is a lie
    block.fold.header.setTitle("${block.name}  ${ModelRows.counter(shown, block.rows.size)}")
    block.allHidden?.isVisible = onlyEnabled && block.rows.isNotEmpty() && afterFilter == 0 && tokens.isEmpty()
    block.fold.revalidate()
    block.fold.repaint()
  }

  private fun updateSummary() {
    val keyed = blocks.values.filter { !it.waiting }
    summary.text = ModelsPage.summary(keyed.size, keyed.sumOf { b -> b.rows.count { it.box.isSelected } })
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
  }
}
