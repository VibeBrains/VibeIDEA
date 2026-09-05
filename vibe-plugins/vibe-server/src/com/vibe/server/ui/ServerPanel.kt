// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server.ui

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBThinOverlappingScrollBar
import com.intellij.util.ui.JBUI
import com.vibe.server.PortConflict
import com.vibe.server.PreviewUrl
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
    onPortConflict = { entry, port, owners -> askPortConflict(entry, port, owners) },
  )

  /**
   * The choice a busy port deserves: free it, step aside for this session, or do nothing.
   *
   * Changing the port in the project's configuration is deliberately NOT among the options — a tool
   * that edits the config to get past its own warning is worse than the warning.
   */
  private fun askPortConflict(entry: ServerEntry, port: Int, owners: List<Long>): PortConflict.Choice {
    val names = owners.joinToString(", ").ifEmpty { t("servers.portOwnerUnknown") }
    var choice = PortConflict.Choice.CANCEL
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
      val answer = com.intellij.openapi.ui.Messages.showDialog(
        project,
        t("servers.portConflict.body", "port" to port, "owners" to names),
        t("servers.portConflict.title", "port" to port),
        arrayOf(t("servers.portConflict.free"), t("servers.portConflict.session"), t("common.cancel")),
        // Freeing the port kills somebody's process, so the safe option is the default one.
        2,
        null,
      )
      choice = when (answer) {
        0 -> PortConflict.Choice.FREE_PORT
        1 -> PortConflict.Choice.SESSION_PORT
        else -> PortConflict.Choice.CANCEL
      }
    }
    return choice
  }

  init {
    border = JBUI.Borders.empty(4)
    val startAll = JButton(t("servers.action.startAll"))
    val stopAll = JButton(t("servers.action.stopAll"))
    val startOne = JButton(t("servers.action.startSelected"))
    val reload = JButton(t("servers.action.reload"))
    // «Превью» — то, ради чего в контракте с самого начала есть previewPath: адрес у записи был,
    // а открыть его было нечем, хотя браузер в IDE есть (панель «Дизайн»).
    val preview = JButton(t("servers.action.preview"))
    preview.addActionListener { openPreview() }
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
      add(startAll); add(stopAll); add(startOne); add(preview); add(reload)
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

  /**
   * Показывает страницу выбранной записи во встроенном браузере.
   *
   * Отказ называется вслух: молчащая кнопка читается как поломка, а причин ровно три — задача не
   * страница, у записи нет порта, встроенного браузера в этой сборке нет.
   */
  private fun openPreview() {
    val idx = list.selectedIndex
    if (idx < 0 || idx >= entries.size) { appendLog(t("servers.preview.noSelection")); return }
    val entry = entries[idx]
    when (val address = PreviewUrl.of(entry)) {
      is PreviewUrl.Address.Refused -> appendLog(when (address.refusal) {
        PreviewUrl.Refusal.NO_PORT -> t("servers.preview.noPort", "id" to entry.id)
        PreviewUrl.Refusal.TASK_HAS_NO_PAGE -> t("servers.preview.task", "id" to entry.id)
      })
      is PreviewUrl.Address.Url -> {
        // Запущенности не требуем: смотреть на «не удалось подключиться» — законный способ понять,
        // что сервис не поднялся, а запрет открывать превью до старта прятал бы это за кнопкой.
        if (com.vibe.agent.preview.PreviewOpener.open(project, address.text)) {
          appendLog(t("servers.preview.opened", "url" to address.text))
        }
        else {
          appendLog(t("servers.preview.noBrowser", "url" to address.text))
        }
      }
    }
  }

  private fun reload() {
    entries = ServersFile.load(project.basePath) { appendLog("[servers.json] $it") }
    // keep live statuses of same ids across re-reads (contract)
    status.keys.retainAll(entries.map { it.id }.toSet())
    if (entries.isEmpty()) appendLog(t("servers.empty"))
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
