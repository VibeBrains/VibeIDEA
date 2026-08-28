// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBThinOverlappingScrollBar
import com.intellij.util.ui.JBUI
import com.vibe.server.ServerEntry
import com.vibe.server.ServerRunner
import com.vibe.server.ServerStatus
import com.vibe.server.ServersFile
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * The stack view: entries with live status; excluded/failed entries carry the
 * reason inline — nothing fails silently. Start All / Stop All / start one.
 */
class ServerPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val listModel = DefaultListModel<String>()
  private val list = JList(listModel)
  private val log = JTextArea().apply {
    isEditable = false
    font = Font(Font.MONOSPACED, Font.PLAIN, 11)
  }
  private var entries: List<ServerEntry> = emptyList()
  private val status = LinkedHashMap<String, Pair<ServerStatus, String?>>()
  private val runner = ServerRunner(
    projectBase = project.basePath ?: ".",
    onStatus = { id, st, reason ->
      status[id] = st to reason
      refreshList()
    },
    onLog = { id, line -> appendLog("[$id] $line") },
  )

  init {
    border = JBUI.Borders.empty(4)
    val startAll = JButton("Запустить всё")
    val stopAll = JButton("Остановить всё")
    val startOne = JButton("Запустить выбранный")
    val reload = JButton("Перечитать файл")
    startAll.addActionListener { pooled { runner.startAll(entries) } }
    stopAll.addActionListener { pooled { runner.stopAll(entries) } }
    startOne.addActionListener {
      val idx = list.selectedIndex
      if (idx >= 0 && idx < entries.size) {
        val id = entries[idx].id
        pooled { runner.startOne(entries, id) }
      }
    }
    reload.addActionListener { reload() }
    val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
      add(startAll); add(stopAll); add(startOne); add(reload)
    }
    add(buttons, BorderLayout.NORTH)
    // Тонкие скроллы — как во всём нашем UI (решение владельца). Обёртки VibeScroll здесь нет:
    // она живёт в плагине vibe-agent, а тянуть межплагинную зависимость ради двух панелей незачем.
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, thinScroll(list), thinScroll(log)).apply { resizeWeight = 0.4 }, BorderLayout.CENTER)
    reload()
  }

  private fun thinScroll(view: java.awt.Component): JBScrollPane = JBScrollPane(view).apply {
    verticalScrollBar = JBThinOverlappingScrollBar(java.awt.Adjustable.VERTICAL)
    horizontalScrollBar = JBThinOverlappingScrollBar(java.awt.Adjustable.HORIZONTAL)
  }

  private fun reload() {
    entries = ServersFile.load(project.basePath) { appendLog("[servers.json] $it") }
    // keep live statuses of same ids across re-reads (contract)
    status.keys.retainAll(entries.map { it.id }.toSet())
    if (entries.isEmpty()) appendLog("Нет стека: создайте .vibe/servers.json (спека — docs/vibe/manuals/serversSpec.md)")
    refreshList()
  }

  private fun refreshList() {
    SwingUtilities.invokeLater {
      listModel.clear()
      for (e in entries) {
        val (st, reason) = status[e.id] ?: (ServerStatus.IDLE to null)
        val port = e.port?.let { " :$it" } ?: ""
        listModel.addElement("${st.name.lowercase().padEnd(8)} ${e.id}$port [${e.kind}]${reason?.let { " — $it" } ?: ""}")
      }
    }
  }

  private fun appendLog(line: String) {
    SwingUtilities.invokeLater {
      log.append(line + "\n")
      log.caretPosition = log.document.length
    }
  }

  private fun pooled(body: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread(body)
  }
}
