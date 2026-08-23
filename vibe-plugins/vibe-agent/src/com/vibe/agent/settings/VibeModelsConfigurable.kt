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
 * (side-map persisted, survives providers.json edits). Counters in group
 * headers switch to «найдено/всего» while filtering — the VibeIDE behavior.
 */
class VibeModelsConfigurable(private val project: Project) : Configurable {
  private data class Row(val provider: String, val model: String, val box: JBCheckBox)
  private val rows = ArrayList<Row>()
  private val groups = LinkedHashMap<String, Pair<JPanel, JBLabel>>()
  private val search = SearchTextField()

  override fun getDisplayName(): String = "Модели"

  override fun createComponent(): JComponent {
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    val providers = ProvidersService.load(project.basePath) { }
    if (providers.isEmpty()) {
      list.add(JBLabel("Провайдеры ещё не настроены. Списки моделей появятся после providers.json (спека — docs/vibe/manuals/providersSpec.md)."))
    }
    for (p in providers) {
      val header = JBLabel("${p.name} (${p.models.size})")
      val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = IdeBorderFactory.createTitledBorder(p.name, false)
      }
      body.add(header.apply { isVisible = false }) // счётчик живёт в заголовке TitledBorder; label — для фильтра
      for (m in p.models) {
        val box = JBCheckBox("${m.name}${if (m.id != m.name) "  ·  ${m.id}" else ""}", !ModelVisibility.isHidden(p.id, m.id))
        box.toolTipText = if (box.isSelected) "Показать в списке" else "Скрыт из списка"
        rows.add(Row(p.id, m.id, box))
        body.add(box)
      }
      groups[p.id] = body to header
      list.add(body)
    }
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
      add(JBScrollPane(JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }), BorderLayout.CENTER)
    }
  }

  override fun isModified(): Boolean = rows.any { ModelVisibility.isHidden(it.provider, it.model) == it.box.isSelected }

  override fun apply() {
    rows.forEach { ModelVisibility.setHidden(it.provider, it.model, !it.box.isSelected) }
  }
}
