// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.vibe.agent.providers.ProvidersService
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Модели: groups per provider with a search box and show/hide toggles
 * (side-map persisted, survives provider file edits). Hand-declared (static)
 * models carry the «кастом» mark, same as the model picker; fetched catalogs
 * are pulled asynchronously so their models can be hidden here too.
 */
// NoScroll: the page scrolls itself (see VibeProvidersConfigurable — the platform wrapper would
// add a horizontal scrollbar sized by unwrapped label widths).
class VibeModelsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
  private data class Row(val provider: String, val model: String, val box: JBCheckBox)
  private val rows = ArrayList<Row>()
  private val groups = LinkedHashMap<String, Pair<JPanel, JBLabel>>()
  private val catalogHints = HashMap<String, JBLabel>()
  private val search = SearchTextField()

  override fun getDisplayName(): String = "Модели"

  override fun createComponent(): JComponent {
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    val providers = ProvidersService.load(project.basePath) { }
    if (providers.isEmpty()) {
      list.add(JBLabel("<html>Активных провайдеров нет. Включите нужные в <code>.vibe/providers/*.jsonc</code> (<code>active: true</code>)<br>или создайте <code>providers.json</code>. Спека — docs/vibe/manuals/providersSpec.md.</html>"))
    }
    for (p in providers) {
      val header = JBLabel("${p.name} (${p.models.size})")
      val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = IdeBorderFactory.createTitledBorder(p.name, false)
      }
      body.add(header.apply { isVisible = false }) // счётчик живёт в заголовке TitledBorder; label — для фильтра
      for (m in p.models) {
        val box = JBCheckBox("${m.name}${if (m.id != m.name) "  ·  ${m.id}" else ""}  · кастом", !ModelVisibility.isHidden(p.id, m.id))
        box.toolTipText = if (box.isSelected) "Показать в списке" else "Скрыт из списка"
        rows.add(Row(p.id, m.id, box))
        body.add(box)
      }
      if (p.models.isEmpty()) {
        val hint = JBLabel("каталог моделей загружается… (нужен ключ — Провайдеры)").apply {
          foreground = com.intellij.ui.JBColor.GRAY
        }
        catalogHints[p.id] = hint
        body.add(hint)
      }
      groups[p.id] = body to header
      list.add(body)
    }
    fetchCatalogs(providers, list)
    search.textEditor.emptyText.text = "Поиск по имени модели…"
    search.addDocumentListener(object : javax.swing.event.DocumentListener {
      fun update() {
        val tokens = search.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (row in rows) {
          val hay = "${row.provider} ${row.model} ${row.box.text}".lowercase()
          row.box.isVisible = tokens.all { hay.contains(it) }
        }
        list.revalidate(); list.repaint()
      }
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = update()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = update()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = update()
    })
    return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      border = JBUI.Borders.empty(8)
      add(search, BorderLayout.NORTH)
      add(JBScrollPane(TracksViewportWidthPanel(list)), BorderLayout.CENTER)
    }
  }

  /** Pull provider model catalogs off the EDT so fetched models can be hidden here, not only static ones. */
  private fun fetchCatalogs(providers: List<com.vibe.agent.providers.ProviderEntry>, list: JPanel) {
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
      val llm = com.vibe.agent.providers.LlmClient()
      for (p in providers) {
        if (p.modelsFetch?.enabled == false) { dropHint(p.id, "модели заданы в файле (fetch: false)", list); continue }
        val resolved = ProvidersService.resolve(p, project.basePath) { }
        if (resolved == null || (resolved.apiKey == null && !resolved.isLocal)) {
          dropHint(p.id, "нет ключа — каталог моделей недоступен (Провайдеры → ключ)", list); continue
        }
        val ids = try { llm.listModels(resolved, p.modelsFetch?.url) }
        catch (e: Exception) { dropHint(p.id, "каталог не получен: ${e.message?.take(80)}", list); continue }
        val known = p.models.map { it.id }.toSet()
        val extra = ids.filter { it !in known }
        javax.swing.SwingUtilities.invokeLater {
          val body = groups[p.id]?.first ?: return@invokeLater
          catalogHints.remove(p.id)?.let { body.remove(it) }
          for (id in extra) {
            val box = JBCheckBox(id, !ModelVisibility.isHidden(p.id, id))
            box.toolTipText = if (box.isSelected) "Показать в списке" else "Скрыт из списка"
            rows.add(Row(p.id, id, box))
            body.add(box)
          }
          list.revalidate(); list.repaint()
        }
      }
    }
  }

  private fun dropHint(providerId: String, text: String, list: JPanel) {
    javax.swing.SwingUtilities.invokeLater {
      catalogHints[providerId]?.text = text
      list.revalidate(); list.repaint()
    }
  }

  override fun isModified(): Boolean = rows.any { ModelVisibility.isHidden(it.provider, it.model) == it.box.isSelected }

  override fun apply() {
    rows.forEach { ModelVisibility.setHidden(it.provider, it.model, !it.box.isSelected) }
  }
}
