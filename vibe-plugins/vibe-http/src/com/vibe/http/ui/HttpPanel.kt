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
  private val list: JBList<HttpRequestFile.Request> = JBList(requests).apply {
    cellRenderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.title }
  }
  private val environment = ComboBox<String>().apply {
    toolTipText = t("http.environment.hint")
    // Выбор запоминается: он терялся при перезапуске IDE, и следующий запрос уходил в dev,
    // пока человек был уверен, что работает с продом.
    addActionListener { (selectedItem as? String)?.let { com.vibe.http.HttpEnvironmentChoice.set(project, it) } }
  }
  private val runButton = JButton(t("http.run"), AllIcons.Actions.Execute).apply {
    isEnabled = false
    addActionListener { runSelected() }
  }

  /**
   * «Копировать как cURL» — в Postman одна из самых нажимаемых кнопок: запрос уходит в тикет,
   * в документацию, коллеге в мессенджер. Переменные подставляются, потому что команда нужна
   * рабочая, а не с «{{host}}» посередине.
   */
  /**
   * «Прогнать всё» — дымовой проход по файлу. По одному запросы гоняют ровно до третьего, дальше
   * перестают; сводка «сколько ответило, что упало» это то, ради чего пишут скрипты на curl.
   */
  private val runAllButton = JButton(t("http.runAll"), AllIcons.Actions.RunAll).apply {
    addActionListener { runAll() }
  }

  private val curlButton = JButton(t("http.copyCurl"), AllIcons.Actions.Copy).apply {
    isEnabled = false
    addActionListener {
      val request = list.selectedValue ?: return@addActionListener
      val (applied, unresolved) = HttpVariables.apply(request, variables()) { dynamic(it) }
      com.intellij.openapi.ide.CopyPasteManager.getInstance()
        .setContents(java.awt.datatransfer.StringSelection(com.vibe.http.CurlConversion.toCurl(applied)))
      statusLine.foreground = JBColor.foreground()
      statusLine.text = if (unresolved.isEmpty()) t("http.copied")
      else t("http.copied") + "  ·  " + t("http.unresolved", "names" to unresolved.joinToString { "{{${it.name}}}" })
    }
  }
  /**
   * Строка состояния СПИСКА: «отправляю», «файл не найден», сводка прогона всех запросов.
   *
   * Не дубль той, что в центре: там пишут про ответ (код, время, размер), здесь — про ход дела.
   * Человек, нажавший «выполнить», должен видеть подтверждение нажатия там же, где нажал.
   */
  private val statusLine = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }

  /** Путь файла, из которого взяты запросы, — по нему ищутся окружения и относительные тела. */
  private var currentDir: Path? = null

  init {
    // Слушатель здесь, а не в объявлении списка: кнопки объявлены ниже, и ссылка на них из
    // инициализатора списка — рекурсия для вывода типов.
    list.addListSelectionListener {
      runButton.isEnabled = list.selectedValue != null
      curlButton.isEnabled = list.selectedValue != null
    }
    val top = JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(4)
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
        add(JBLabel(t("http.environment")))
        add(environment)
        add(runButton)
        add(runAllButton)
        add(curlButton)
        add(JButton(t("http.reload"), AllIcons.Actions.Refresh).apply { addActionListener { reload() } })
      }, BorderLayout.WEST)
    }
    add(top, BorderLayout.NORTH)
    add(VibeScroll.pane(list), BorderLayout.CENTER)
    add(statusLine, BorderLayout.SOUTH)
    reload()
  }

  /**
   * Обновление интерфейса из фонового потока — только пока проект жив.
   *
   * Запрос может идти секунды, а проект за это время закрывают: `invokeLater` в закрытую панель
   * даёт исключение в логе и выглядит как «IDE ругается сама на себя».
   */
  private fun onUi(block: () -> Unit) {
    SwingUtilities.invokeLater {
      if (project.isDisposed || !isDisplayable) return@invokeLater
      block()
    }
  }

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
    // Находки разбора называются вслух: до ревизии 03.09.2026 они считались и молча выбрасывались,
    // то есть человек с опечаткой в заголовке видел «запросов: 3» и никакого намёка на четвёртый.
    statusLine.foreground = JBColor.foreground()
    statusLine.text = if (parsed.problems.isEmpty()) t("http.requestsFound", "count" to parsed.requests.size)
    else t("http.requestsFoundWithProblems", "count" to parsed.requests.size,
           "problems" to parsed.problems.joinToString("; ") { problem ->
             val what = when (problem.trouble) {
               HttpRequestFile.Trouble.NOT_A_REQUEST -> t("http.problem.notARequest")
               HttpRequestFile.Trouble.NOT_A_HEADER -> t("http.problem.notAHeader")
               HttpRequestFile.Trouble.TIMEOUT_NOT_A_NUMBER -> t("http.problem.timeout")
             }
             t("http.problem.at", "line" to (problem.line + 1), "what" to what)
           })
    fillEnvironments()
  }

  private fun fillEnvironments() {
    val names = com.vibe.http.HttpEnvironmentChoice.names(project, currentDir)
    val previous = com.vibe.http.HttpEnvironmentChoice.get(project) ?: environment.selectedItem as? String
    environment.removeAllItems()
    names.forEach(environment::addItem)
    // Исчезнувшее окружение не залипает: сохранённое имя без файла откатывается на первое по
    // алфавиту, иначе панель предлагала бы то, чего нет.
    HttpEnvironments.choose(names.toSet(), previous)?.let { environment.selectedItem = it }
  }

  /** Одно место сборки переменных на панель, подсказки и подсветку — иначе они разойдутся. */
  private fun variables(): Map<String, String> = com.vibe.http.HttpEnvironmentChoice.variables(
    project, currentDir, FileEditorManager.getInstance(project).selectedTextEditor?.document?.text)

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
    // Ответ показывается в центре — там его читают. Вкладка открывается ДО отправки, чтобы
    // человек видел, куда смотреть, пока запрос идёт.
    val response = HttpWorkbench.getInstance(project).openResponse()
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = VibeHttpService.getInstance(project).send(applied, dir)
      onUi { response?.show(result, unresolved, applied) }
    }
  }

  /**
   * Выполняет все запросы файла подряд и показывает сводку.
   *
   * Изменяющие запросы называются ДО прогона и требуют подтверждения: `POST /users` пять раз — это
   * пять пользователей. Не запрещаем: файл писал человек, и он знает, что там.
   */
  private fun runAll() {
    val all = (0 until requests.size()).map { requests.get(it) }
    if (all.isEmpty()) return
    val changing = com.vibe.http.HttpRunAll.changingRequests(all)
    if (changing.isNotEmpty()) {
      val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
        project,
        t("http.runAll.confirm", "count" to changing.size, "methods" to changing.joinToString { it.method }),
        t("http.runAll.confirmTitle"),
        t("http.runAll.yes"), t("http.runAll.no"), AllIcons.General.WarningDialog,
      )
      if (answer != com.intellij.openapi.ui.Messages.YES) return
    }
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("http.runAll.running", "count" to all.size)
    val dir = currentDir
    val values = variables()
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = VibeHttpService.getInstance(project)
      val outcomes = all.map { request ->
        val (applied, unresolved) = HttpVariables.apply(request, values) { dynamic(it) }
        if (unresolved.isNotEmpty()) {
          com.vibe.http.HttpRunAll.Outcome.Refused(applied.title, unresolved.joinToString { "{{${it.name}}}" })
        }
        else when (val result = service.send(applied, dir)) {
          is VibeHttpService.Result.Done ->
            com.vibe.http.HttpRunAll.Outcome.Answered(applied.title, result.response.status, result.response.durationMs)
          is VibeHttpService.Result.Refused ->
            com.vibe.http.HttpRunAll.Outcome.Refused(applied.title, result.refusal.detail)
          is VibeHttpService.Result.Failed ->
            com.vibe.http.HttpRunAll.Outcome.Failed(applied.title, result.message)
        }
      }
      val summary = com.vibe.http.HttpRunAll.summarize(outcomes)
      onUi {
        statusLine.foreground = if (summary.allOk)
          JBColor.namedColor("Vibe.Http.success", JBColor(0x208A3C, 0x57965C))
        else JBColor.namedColor("Vibe.Http.error", JBColor(0xDB3B4B, 0xDB5C5C))
        statusLine.text = t("http.runAll.done", "ok" to summary.ok, "total" to summary.total,
                            "ms" to summary.totalMs)
        // Упавшие называем поимённо — и в той же панели, где читают ответы: сводка без имён
        // заставляет гонять по одному заново, ровно то, от чего прогон и избавляет.
        HttpWorkbench.getInstance(project).openResponse()?.showText(
          if (summary.failed.isEmpty()) t("http.runAll.allOk")
          else t("http.runAll.failedList") + "\n" + summary.failed.joinToString("\n") { "  • " + it })
      }
    }
  }

  /** `{{$uuid}}` и соседи. Снаружи — чтобы в тестах разбора не зависеть от времени и случайности. */
  private fun dynamic(name: String): String? = when (name) {
    "uuid" -> java.util.UUID.randomUUID().toString()
    "timestamp" -> (System.currentTimeMillis() / 1000).toString()
    "randomInt" -> (0..1000).random().toString()
    else -> null
  }

}
