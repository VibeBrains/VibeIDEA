// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersChangeListener
import com.vibe.agent.providers.ProvidersService
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Модели: collapsible provider groups (VibeIDE §4 — all collapsed by default, chevron
 * header with a (N) / (X/Y) counter), AND-tokenized search over name/id/provider/traits,
 * the «активные» display-only filter (on by default), trait badges on rows («кастом»,
 * vision/text-only, fim, context size), and async catalog pull so fetched models can be
 * hidden here too. Visibility lives in the [ModelVisibility] side-map; Apply notifies
 * open panels via [ProvidersChangeListener] so the model picker updates without a restart.
 */
// NoScroll: the page scrolls itself (see VibeProvidersConfigurable — the platform wrapper would
// add a horizontal scrollbar sized by unwrapped label widths).
class VibeModelsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
  private class Row(val providerId: String, val modelId: String, val hay: String, val box: JBCheckBox)

  private class Group(val providerId: String, val name: String, val header: JBLabel, val body: JPanel, val container: JPanel) {
    var expanded = false // manual state; searching temporarily force-expands matching groups
  }

  private val rows = ArrayList<Row>()
  private val groups = LinkedHashMap<String, Group>()
  private val catalogHints = HashMap<String, JBLabel>()
  private val search = SearchTextField()
  private val activesOnly = JBCheckBox("активные", true).apply {
    toolTipText = "Показывать в списке только модели, включённые переключателем (видимые в списках выбора модели)."
  }
  private var listPanel: JPanel? = null

  override fun getDisplayName(): String = "Модели"

  override fun createComponent(): JComponent {
    rows.clear(); groups.clear(); catalogHints.clear()
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    listPanel = list
    val providers = ProvidersService.load(project.basePath) { }
    if (providers.isEmpty()) {
      list.add(JBLabel("<html>Активных провайдеров нет. Включите нужные в <code>.vibe/providers/*.jsonc</code> (<code>active: true</code>)<br>или создайте <code>providers.json</code>. Спека — docs/vibe/manuals/providersSpec.md.</html>"))
    }
    for (p in providers) {
      val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyLeft(20)
        isVisible = false // all groups collapsed by default (VibeIDE §4)
      }
      val header = JBLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(6, 2, 2, 2)
      }
      val container = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
      }
      val group = Group(p.id, p.name, header, body, container)
      header.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          group.expanded = !group.expanded
          update()
        }
      })
      for (m in p.models) {
        addRow(group, m.id, ModelRows.label(m.name, m.id, ModelRows.badges(m, custom = true)))
      }
      if (p.models.isEmpty()) {
        val hint = JBLabel("каталог моделей загружается… (нужен ключ — Провайдеры)").apply {
          foreground = com.intellij.ui.JBColor.GRAY
        }
        catalogHints[p.id] = hint
        body.add(hint)
      }
      groups[p.id] = group
      list.add(container)
    }
    search.textEditor.emptyText.text = "Поиск: имя, id, провайдер, vision/fim/кастом…"
    search.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = update()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = update()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = update()
    })
    activesOnly.addActionListener { update() }
    fetchCatalogs(providers)
    update()
    return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      border = JBUI.Borders.empty(8)
      add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        add(search, BorderLayout.CENTER)
        add(activesOnly, BorderLayout.EAST)
      }, BorderLayout.NORTH)
      add(JBScrollPane(TracksViewportWidthPanel(list)), BorderLayout.CENTER)
    }
  }

  private fun addRow(group: Group, modelId: String, label: String) {
    val box = JBCheckBox(label, !ModelVisibility.isHidden(group.providerId, modelId))
    box.toolTipText = if (box.isSelected) "Показать в списке" else "Скрыт из списка"
    box.addActionListener {
      box.toolTipText = if (box.isSelected) "Показать в списке" else "Скрыт из списка"
      if (activesOnly.isSelected) update()
    }
    rows.add(Row(group.providerId, modelId, "${group.name} ${group.providerId} $label", box))
    group.body.add(box)
  }

  /** Recompute row/group visibility, counters and search-driven expansion. */
  private fun update() {
    val tokens = ModelRows.tokens(search.text)
    val searching = tokens.isNotEmpty()
    for (group in groups.values) {
      val groupRows = rows.filter { it.providerId == group.providerId }
      val afterActives = groupRows.filter { !activesOnly.isSelected || it.box.isSelected }
      val found = afterActives.filter { ModelRows.matches(it.hay, tokens) }
      for (r in groupRows) r.box.isVisible = r in found
      val chevron = if (searching || group.expanded) "▾" else "▸"
      group.header.text = "$chevron  ${group.name}  ${ModelRows.counter(found.size, afterActives.size, searching)}"
      group.header.toolTipText = "${groupRows.size} всего"
      // While searching: only groups with matches are visible and they are force-expanded;
      // otherwise the manual collapsed/expanded state rules (collapsed by default).
      group.container.isVisible = !searching || found.isNotEmpty()
      group.body.isVisible = if (searching) true else group.expanded
    }
    listPanel?.revalidate(); listPanel?.repaint()
  }

  /** Pull provider model catalogs off the EDT so fetched models can be hidden here, not only static ones. */
  private fun fetchCatalogs(providers: List<ProviderEntry>) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val llm = LlmClient()
      for (p in providers) {
        if (p.modelsFetch?.enabled == false) { dropHint(p.id, "модели заданы в файле (fetch: false)"); continue }
        val resolved = ProvidersService.resolve(p, project.basePath) { }
        if (resolved == null || (resolved.apiKey == null && !resolved.isLocal)) {
          dropHint(p.id, "нет ключа — каталог моделей недоступен (Провайдеры → ключ)"); continue
        }
        val ids = try { llm.listModels(resolved, p.modelsFetch?.url) }
        catch (e: Exception) { dropHint(p.id, "каталог не получен: ${e.message?.take(80)}"); continue }
        val known = p.models.map { it.id }.toSet()
        val extra = ids.filter { it !in known }
        SwingUtilities.invokeLater {
          val group = groups[p.id] ?: return@invokeLater
          catalogHints.remove(p.id)?.let { group.body.remove(it) }
          for (id in extra) addRow(group, id, id)
          update()
        }
      }
    }
  }

  private fun dropHint(providerId: String, text: String) {
    SwingUtilities.invokeLater {
      catalogHints[providerId]?.text = text
      listPanel?.revalidate(); listPanel?.repaint()
    }
  }

  override fun isModified(): Boolean = rows.any { ModelVisibility.isHidden(it.providerId, it.modelId) == it.box.isSelected }

  override fun apply() {
    var changed = false
    for (r in rows) {
      val hide = !r.box.isSelected
      if (ModelVisibility.isHidden(r.providerId, r.modelId) != hide) {
        ModelVisibility.setHidden(r.providerId, r.modelId, hide)
        changed = true
      }
    }
    // The picker filters hidden models when building targets — tell open panels to rebuild.
    if (changed) project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
  }
}
