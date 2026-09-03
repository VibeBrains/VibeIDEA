// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import com.vibe.http.HttpEnvironments
import com.vibe.http.HttpExchange
import com.vibe.http.HttpRequestFile
import com.vibe.http.HttpVariables
import com.vibe.http.VibeHttpService
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Панель клиента: слева запросы открытого файла, справа ответ.
 *
 * Форма взята у Postman, потому что она проверена миллионами людей: выбранное окружение и кнопка
 * запуска сверху, список запросов слева, ответ справа со строкой «статус · время · размер». Всё
 * остальное — файлы в репозитории, а не коллекции в облаке.
 */
class HttpPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val requests = DefaultListModel<HttpRequestFile.Request>()
  private val list = JBList(requests).apply {
    cellRenderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.title }
    addListSelectionListener { runButton.isEnabled = selectedValue != null }
  }
  private val environment = ComboBox<String>().apply { toolTipText = t("http.environment.hint") }
  private val runButton = JButton(t("http.run"), AllIcons.Actions.Execute).apply {
    isEnabled = false
    addActionListener { runSelected() }
  }
  private val statusLine = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }
  private val bodyView = area()
  private val headersView = area()
  private val tabs = JBTabbedPane().apply {
    addTab(t("http.tab.body"), VibeScroll.pane(bodyView))
    addTab(t("http.tab.headers"), VibeScroll.pane(headersView))
  }

  /** Путь файла, из которого взяты запросы, — по нему ищутся окружения и относительные тела. */
  private var currentDir: Path? = null

  init {
    val top = JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(4)
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
        add(JBLabel(t("http.environment")))
        add(environment)
        add(runButton)
        add(JButton(t("http.reload"), AllIcons.Actions.Refresh).apply { addActionListener { reload() } })
      }, BorderLayout.WEST)
    }
    val left = JPanel(BorderLayout()).apply {
      add(VibeScroll.pane(list), BorderLayout.CENTER)
      preferredSize = Dimension(260, 0)
    }
    val right = JPanel(BorderLayout()).apply {
      add(statusLine, BorderLayout.NORTH)
      add(tabs, BorderLayout.CENTER)
    }
    add(top, BorderLayout.NORTH)
    add(com.intellij.ui.OnePixelSplitter(false, 0.32f).apply {
      firstComponent = left
      secondComponent = right
    }, BorderLayout.CENTER)
    reload()
  }

  private fun area() = JTextArea().apply {
    isEditable = false
    lineWrap = false
    font = com.intellij.util.ui.JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
    border = JBUI.Borders.empty(6)
  }

  /** Перечитывает открытый файл: список запросов и окружения берутся из него. */
  fun reload() {
    val editor: Editor? = FileEditorManager.getInstance(project).selectedTextEditor
    val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    requests.clear()
    if (editor == null || file == null || file.extension?.lowercase() !in com.vibe.http.HttpFileType.EXTENSIONS) {
      statusLine.text = t("http.noFile")
      currentDir = null
      environment.removeAllItems()
      return
    }
    currentDir = runCatching { Path.of(file.parent.path) }.getOrNull()
    val parsed = HttpRequestFile.parse(editor.document.text)
    parsed.requests.forEach(requests::addElement)
    statusLine.text = t("http.requestsFound", "count" to parsed.requests.size)
    fillEnvironments()
  }

  private fun fillEnvironments() {
    val root = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
    val found = HttpEnvironments.locate(currentDir, root)
    val names = HttpEnvironments.read(found).keys.sorted()
    val previous = environment.selectedItem as? String
    environment.removeAllItems()
    names.forEach(environment::addItem)
    HttpEnvironments.choose(names.toSet(), previous)?.let { environment.selectedItem = it }
  }

  private fun variables(): Map<String, String> {
    val root = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
    val environments = HttpEnvironments.read(HttpEnvironments.locate(currentDir, root))
    val chosen = environment.selectedItem as? String
    val fromFile = FileEditorManager.getInstance(project).selectedTextEditor
      ?.let { HttpRequestFile.parse(it.document.text).variables }.orEmpty()
    // Переменные файла сильнее окружения: их пишут рядом с запросом именно затем, чтобы перекрыть.
    return environments[chosen].orEmpty() + fromFile
  }

  private fun runSelected() {
    val request = list.selectedValue ?: return
    run(request)
  }

  /** Выполняет запрос и показывает ответ; вызывается и из действия в редакторе. */
  fun run(request: HttpRequestFile.Request) {
    val (applied, unresolved) = HttpVariables.apply(request, variables()) { dynamic(it) }
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("http.sending", "request" to applied.title)
    val dir = currentDir
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = VibeHttpService.getInstance(project).send(applied, dir)
      SwingUtilities.invokeLater { show(result, unresolved) }
    }
  }

  /** Слово к числу подбирает интерфейс: единицы — такой же переводимый текст, как всё остальное. */
  private fun size(bytes: Long): String {
    val size = HttpExchange.size(bytes)
    val value = if (size.unit == HttpExchange.SizeUnit.BYTES) size.value.toLong().toString()
                else String.format("%.1f", size.value)
    return when (size.unit) {
      HttpExchange.SizeUnit.BYTES -> t("http.size.bytes", "value" to value)
      HttpExchange.SizeUnit.KIB -> t("http.size.kib", "value" to value)
      HttpExchange.SizeUnit.MIB -> t("http.size.mib", "value" to value)
    }
  }

  private fun duration(ms: Long): String {
    val duration = HttpExchange.duration(ms)
    return if (duration.inSeconds) t("http.time.seconds", "value" to String.format("%.1f", duration.value))
    else t("http.time.ms", "value" to duration.value.toLong().toString())
  }

  /** `{{$uuid}}` и соседи. Снаружи — чтобы в тестах разбора не зависеть от времени и случайности. */
  private fun dynamic(name: String): String? = when (name) {
    "uuid" -> java.util.UUID.randomUUID().toString()
    "timestamp" -> (System.currentTimeMillis() / 1000).toString()
    "randomInt" -> (0..1000).random().toString()
    else -> null
  }

  private fun show(result: VibeHttpService.Result, unresolved: List<HttpVariables.Unresolved>) {
    when (result) {
      is VibeHttpService.Result.Done -> {
        val response = result.response
        statusLine.foreground = when (HttpExchange.outcome(response.status)) {
          HttpExchange.Outcome.SUCCESS -> JBColor.namedColor("Vibe.Http.success", JBColor(0x208A3C, 0x57965C))
          HttpExchange.Outcome.REDIRECT -> JBColor.namedColor("Vibe.Http.redirect", JBColor(0xC27D04, 0xD6AE58))
          else -> JBColor.namedColor("Vibe.Http.error", JBColor(0xDB3B4B, 0xDB5C5C))
        }
        statusLine.text = t(
          "http.status",
          "status" to response.status,
          "time" to duration(response.durationMs),
          "size" to size(response.sizeBytes),
        )
        bodyView.text = if (HttpExchange.looksLikeJson(response)) HttpExchange.prettyJson(response.body) else response.body
        bodyView.caretPosition = 0
        headersView.text = response.headers.joinToString("\n") { "${it.name}: ${it.value}" }
      }
      is VibeHttpService.Result.Refused -> {
        statusLine.foreground = JBColor.namedColor("Vibe.Http.error", JBColor(0xDB3B4B, 0xDB5C5C))
        statusLine.text = when (result.refusal.reason) {
          com.vibe.http.HttpCall.Reason.UNRESOLVED_VARIABLE -> t("http.refused.variable", "name" to result.refusal.detail)
          com.vibe.http.HttpCall.Reason.NO_SCHEME -> t("http.refused.scheme", "target" to result.refusal.detail)
          com.vibe.http.HttpCall.Reason.BAD_TARGET -> t("http.refused.target", "target" to result.refusal.detail)
          com.vibe.http.HttpCall.Reason.BODY_FILE_MISSING -> t("http.refused.bodyFile", "path" to result.refusal.detail)
        }
      }
      is VibeHttpService.Result.Failed -> {
        statusLine.foreground = JBColor.namedColor("Vibe.Http.error", JBColor(0xDB3B4B, 0xDB5C5C))
        statusLine.text = t("http.failed", "reason" to result.message)
      }
    }
    // Неподставленные переменные называем ОТДЕЛЬНО и после ответа: сервер мог ответить и на кривой
    // адрес, и тогда «200 OK» без этой строки означал бы, что всё в порядке.
    if (unresolved.isNotEmpty()) {
      statusLine.text = statusLine.text + "  ·  " + t("http.unresolved", "names" to unresolved.joinToString { "{{${it.name}}}" })
    }
  }
}
