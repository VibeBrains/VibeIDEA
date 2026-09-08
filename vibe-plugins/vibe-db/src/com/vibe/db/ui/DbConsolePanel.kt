// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import com.vibe.db.CsvExport
import com.vibe.db.DataSources
import com.vibe.db.DbCatalog
import com.vibe.db.DbSettings
import com.vibe.db.JdbcSession
import com.vibe.db.QueryLimit
import com.vibe.db.SqlStatements
import com.vibe.db.VibeDbService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Консоль запросов и результат — в ЦЕНТРАЛЬНОЙ панели.
 *
 * Концепция владельца 08.09.2026: сбоку действия и навигация, в центре то, с чем работают. Грид
 * результата — ровно «то, с чем работают»: его читают, правят ячейки, копируют строки и
 * выгружают. В колонке шириной с дерево проекта всё это делать нельзя, а в центре — можно.
 *
 * Подключение и прочитанные схемы берутся из [DbWorkbench]: их знает навигатор, и вторая копия
 * разошлась бы с первой ровно тогда, когда человек переключит подключение.
 */
class DbConsolePanel(private val project: Project) : JPanel(BorderLayout()) {
  /**
   * Консоль запросов — редактор платформы, а не текстовое поле.
   *
   * Community не несёт поддержки SQL вовсе, поэтому запрос был чёрным текстом: в нём не отличались
   * ключевое слово, строка и комментарий — а глаз находит ошибку до запуска именно по ним.
   * `LanguageTextField` на нашем языке `VibeSql` даёт подсветку, выделение парных скобок и
   * настройки шрифта редактора бесплатно; своё рисование подсветки поверх `JTextArea` пришлось бы
   * поддерживать вместе с темой.
   */
  private val console = com.intellij.ui.LanguageTextField(
    com.vibe.db.SqlLanguage, project, "", false,
  ).apply {
    border = JBUI.Borders.empty(6)
    setOneLineMode(false)
    preferredSize = Dimension(0, CONSOLE_HEIGHT)
  }
  private val results = object : JBTable() {
    /**
     * Правка, которой ещё нет в базе, красится отдельно.
     *
     * Иначе набранное и сохранённое выглядят одинаково, и человек уходит с панели в уверенности,
     * что изменил данные, — а не изменил ничего.
     */
    override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): java.awt.Component {
      val component = super.prepareRenderer(renderer, row, column)
      val model = model as? ResultTableModel
      if (model != null && !isCellSelected(row, column)) {
        component.background =
          if (model.isEdited(convertRowIndexToModel(row), convertColumnIndexToModel(column)))
            JBColor.namedColor("Vibe.Db.editedCell", JBColor(0xFFF3C4, 0x4A4327))
          else background
      }
      return component
    }
  }.apply {
    autoResizeMode = javax.swing.JTable.AUTO_RESIZE_OFF
    // Правая кнопка на строке: скопировать в тикет, повторить на другом стенде, найти снова.
    // Без этого человек выделяет ячейки по одной и склеивает руками — и ошибается в кавычках.
    componentPopupMenu = javax.swing.JPopupMenu().apply {
      add(javax.swing.JMenuItem(t("db.copy.json")).apply { addActionListener { copyRow(RowFormat.JSON) } })
      add(javax.swing.JMenuItem(t("db.copy.insert")).apply { addActionListener { copyRow(RowFormat.INSERT) } })
      add(javax.swing.JMenuItem(t("db.copy.where")).apply { addActionListener { copyRow(RowFormat.WHERE) } })
    }
  }

  private enum class RowFormat { JSON, INSERT, WHERE }
  private val statusLine = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }

  private var lastTable: com.vibe.db.ResultTable.Table? = null
  private var lastQuery: String = ""
  private var editTarget: com.vibe.db.RowEdit.Target? = null

  private val exportButton = JButton(t("db.export"), AllIcons.ToolbarDecorator.Export).apply {
    isEnabled = false
    addActionListener { exportCsv() }
  }
  private val applyButton = JButton(t("db.edit.apply"), AllIcons.Actions.Commit).apply {
    isVisible = false
    addActionListener { applyEdits() }
  }
  private val discardButton = JButton(t("db.edit.discard"), AllIcons.Actions.Rollback).apply {
    isVisible = false
    addActionListener { discardEdits() }
  }

  private val workbench get() = DbWorkbench.getInstance(project)

  init {
    // Ctrl+Space — тот же жест, что и везде в IDE; локальным шорткатом, а не слушателем клавиш:
    // в редакторе платформы нажатия сначала проходят через её диспетчер.
    object : com.intellij.openapi.actionSystem.AnAction() {
      override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = suggestCompletion()
    }.registerCustomShortcutSet(
      com.intellij.openapi.actionSystem.CustomShortcutSet(
        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE, java.awt.event.InputEvent.CTRL_DOWN_MASK)),
      console,
    )
    val consolePanel = JPanel(BorderLayout()).apply {
      add(console, BorderLayout.CENTER)
      add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(JButton(t("db.run"), AllIcons.Actions.Execute).apply { addActionListener { runConsole() } })
        add(exportButton)
        add(applyButton)
        add(discardButton)
        add(JBLabel(t("db.run.hint")).apply { foreground = JBColor.GRAY })
      }, BorderLayout.SOUTH)
    }
    add(OnePixelSplitter(true, 0.3f).apply {
      firstComponent = consolePanel
      secondComponent = JPanel(BorderLayout()).apply {
        add(statusLine, BorderLayout.NORTH)
        add(VibeScroll.pane(results), BorderLayout.CENTER)
      }
    }, BorderLayout.CENTER)
    workbench.console = this
  }

  /** Предпросмотр таблицы: запрос ставится в консоль, чтобы его было видно и можно было править. */
  fun showTable(table: DbCatalog.Table) {
    val sql = QueryLimit.preview(table.name, table.schema, DbSettings.previewRows)
    console.text = sql
    execute(sql, table)
  }

  private fun onUi(block: () -> Unit) {
    SwingUtilities.invokeLater {
      if (project.isDisposed || !isDisplayable) return@invokeLater
      block()
    }
  }

  private fun source(): DataSources.DataSource? = workbench.source

  /**
   * Выполняет оператор, пришедший из файла `.sql`.
   *
   * Через ту же проверку на изменяющий оператор, что и консоль: подтверждение зависит от того, ЧТО
   * уйдёт в базу, а не от того, откуда нажали.
   */
  fun runFromEditor(sql: String) {
    console.text = sql
    if (!confirmWrite(sql)) return
    execute(sql)
  }

  /** @return можно ли выполнять: для выборки — сразу, для изменения — после подтверждения. */
  private fun confirmWrite(sql: String): Boolean {
    if (SqlStatements.isReadOnly(sql)) return true
    val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
      project,
      t("db.confirmWrite.body", "sql" to sql.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120),
        "source" to (source()?.name ?: "")),
      t("db.confirmWrite.title"),
      t("db.confirmWrite.yes"),
      t("db.confirmWrite.no"),
      AllIcons.General.WarningDialog,
    )
    return answer == com.intellij.openapi.ui.Messages.YES
  }

  private fun runConsole() {
    val statements = SqlStatements.split(console.text)
    val line = caretOffset().let { position -> console.text.take(position).count { it == '\n' } }
    val statement = SqlStatements.statementAt(statements, line) ?: statements.firstOrNull() ?: return
    // Изменяющий оператор спрашивает подтверждение: «выполнить» под курсором легко нажать
    // случайно, а DELETE без WHERE отменить нельзя ничем.
    if (!confirmWrite(statement.text)) return
    execute(statement.text)
  }

  /**
   * Выгружает показанное в CSV.
   *
   * Именно показанное, а не «повторим запрос и выгрузим»: между показом и нажатием данные могли
   * измениться, и человек получил бы файл, не совпадающий с тем, на что он смотрел.
   */
  /**
   * Копирует выделенную строку в выбранном виде.
   *
   * Имя таблицы для INSERT берём из выделенного узла дерева, если он есть; иначе из запроса не
   * угадываем — подставленное наугад имя дало бы запрос, который выполнится не туда.
   */
  private fun copyRow(format: RowFormat) {
    val table = lastTable ?: return
    val viewRow = results.selectedRow
    if (viewRow < 0) return
    val row = table.rows.getOrNull(results.convertRowIndexToModel(viewRow)) ?: return
    // Имя берём у ПОКАЗАННОЙ таблицы, а не у выделения в дереве: копируют строку из того, что
    // на экране, и подставленное из другого места имя дало бы запрос, уходящий не туда.
    val selected = editTarget?.table?.let { DbCatalog.Table(editTarget?.schema, it, DbCatalog.Kind.TABLE) }
    val text = when (format) {
      RowFormat.JSON -> com.vibe.db.RowExport.toJson(table.columns, row) { t("db.binary", "bytes" to it) }
      RowFormat.INSERT -> com.vibe.db.RowExport.toInsert(
        selected?.name ?: t("db.copy.tablePlaceholder"), selected?.schema, table.columns, row)
      RowFormat.WHERE -> com.vibe.db.RowExport.toWhere(table.columns, row, table.columns.map { it.label })
    }
    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text))
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("db.copy.done")
  }

  private fun exportCsv() {
    val table = lastTable ?: return
    val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
      t("db.export.title"), t("db.export.description"), "csv")
    val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
      .createSaveFileDialog(descriptor, project)
    val target = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, CsvExport.fileName(lastQuery)) ?: return
    val text = CsvExport.render(table, nullText = "", binaryText = { t("db.binary", "bytes" to it) })
    runCatching { Files.writeString(target.file.toPath(), text) }
      .onSuccess { statusLine.text = t("db.exported", "path" to target.file.path, "count" to table.rowCount) }
      .onFailure { fail(t("db.exportFailed", "reason" to (it.message ?: ""))) }
  }

  private fun execute(sql: String, editable: DbCatalog.Table? = null) {
    val source = source() ?: return
    lastQuery = sql
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("db.running")
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = VibeDbService.getInstance(project)
      val connection = service.connect(source).getOrElse { error ->
        onUi { fail(t("db.connectFailed", "source" to DataSources.maskUrl(source.url), "reason" to (error.message ?: ""))) }
        return@executeOnPooledThread
      }
      var target: com.vibe.db.RowEdit.Target? = null
      // Ключ читаем в том же подключении, что и данные: отдельное подключение ради метаданных —
      // лишний вход в базу, а на боевой базе входы считают.
      val outcome = connection.use { open ->
        val result = service.execute(open, sql)
        target = if (editable == null) null else com.vibe.db.RowEdit.Target(
          schema = editable.schema,
          table = editable.name,
          keyColumns = service.primaryKey(open, editable.schema, editable.name),
          readOnly = source.readOnly,
        )
        result
      }
      onUi { show(outcome, target) }
    }
  }

  private fun show(outcome: JdbcSession.Outcome, target: com.vibe.db.RowEdit.Target? = null) {
    when (outcome) {
      is JdbcSession.Outcome.Rows -> {
        lastTable = outcome.table
        editTarget = target
        exportButton.isEnabled = outcome.table.rowCount > 0
        val model = ResultTableModel(outcome.table, target)
        model.addTableModelListener { refreshEditButtons() }
        results.model = model
        refreshEditButtons()
        // Ширина по содержимому: таблица должна открываться уже читаемой, а не после ручной подгонки.
        // Ширина в пикселях считается по МЕТРИКАМ шрифта таблицы, а не «символ ≈ 8 пикселей»:
        // на другом шрифте и другом масштабе экрана эта восьмёрка врёт, и колонки едут.
        val charWidth = results.getFontMetrics(results.font).charWidth('0').coerceAtLeast(1)
        for (index in outcome.table.columns.indices) {
          val chars = com.vibe.db.ResultTable.preferredWidth(outcome.table.columns[index], outcome.table.rows, index, t("db.null"))
          results.columnModel.getColumn(index).preferredWidth = chars * charWidth + COLUMN_PADDING
        }
        statusLine.foreground = JBColor.foreground()
        statusLine.text = if (outcome.table.truncated)
          t("db.rowsTruncated", "count" to outcome.table.rowCount, "ms" to outcome.elapsedMs)
        else t("db.rows", "count" to outcome.table.rowCount, "ms" to outcome.elapsedMs)
      }
      is JdbcSession.Outcome.Updated -> {
        lastTable = null
        editTarget = null
        exportButton.isEnabled = false
        results.model = javax.swing.table.DefaultTableModel()
        refreshEditButtons()
        statusLine.foreground = JBColor.foreground()
        statusLine.text = t("db.updated", "count" to outcome.count, "ms" to outcome.elapsedMs)
      }
      is JdbcSession.Outcome.Failed -> fail(t("db.queryFailed", "reason" to outcome.message))
    }
  }

  /**
   * Подсказка по схеме подключения.
   *
   * Схема берётся из уже прочитанного дерева, а столбцы — из кеша: подсказка не имеет права идти
   * в базу по нажатию клавиши. Столбцы нечитанной таблицы дочитываются один раз, в фоне.
   */
  private fun suggestCompletion() {
    val caret = caretOffset()
    val suggestions = com.vibe.db.SqlCompletion.suggest(console.text, caret, workbench.schemas) { table ->
      workbench.columns[table] ?: emptyList()
    }
    if (suggestions.isEmpty()) {
      // Молчать нельзя: нажатие без ответа читается как «подсказок нет в принципе».
      statusLine.foreground = JBColor.foreground()
      statusLine.text = if (workbench.schemas.isEmpty()) t("db.completion.noSchema") else t("db.completion.nothing")
      loadMissingColumns()
      return
    }
    val prefix = com.vibe.db.SqlCompletion.currentWord(console.text.take(caret))
    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
      .createPopupChooserBuilder(suggestions)
      .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { "\${it.text}   \${it.detail}" })
      .setTitle(t("db.completion.title"))
      .setItemChosenCallback { chosen ->
        // Правка документа редактора идёт только под write action, иначе платформа падает.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
          console.document.replaceString(caret - prefix.length, caret, chosen.text)
        }
      }
      .createPopup()
      .showInBestPositionFor(com.intellij.ide.DataManager.getInstance().getDataContext(console))
  }

  /** Дочитывает столбцы таблиц, упомянутых в запросе: иначе подсказка знает только раскрытые узлы. */
  private fun loadMissingColumns() {
    val mentioned = com.vibe.db.SqlCompletion.visibleTables(console.text, workbench.schemas).filterNot { workbench.columns.containsKey(it) }
    if (mentioned.isEmpty()) return
    val source = source() ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = VibeDbService.getInstance(project)
      val connection = service.connect(source).getOrNull() ?: return@executeOnPooledThread
      val loaded = connection.use { open -> mentioned.associateWith { service.columns(open, it) } }
      onUi { workbench.columns.putAll(loaded) }
    }
  }

  private fun editModel(): ResultTableModel? = results.model as? ResultTableModel

  private fun refreshEditButtons() {
    val pending = editModel()?.hasEdits() == true
    applyButton.isVisible = pending
    discardButton.isVisible = pending
    if (pending) {
      statusLine.foreground = JBColor.foreground()
      statusLine.text = t("db.edit.pending", "count" to (editModel()?.pendingStatements()?.size ?: 0))
    }
  }

  private fun discardEdits() {
    editModel()?.discardEdits()
    refreshEditButtons()
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("db.edit.discarded")
  }

  /**
   * Применяет накопленные правки — показав сперва сами операторы.
   *
   * Показ не формальность: человек правил ячейки, а в базу уходит `UPDATE … WHERE ключ`, и увидеть
   * этот текст он должен ДО выполнения, а не в логе после. Отказ — обычный исход диалога.
   */
  private fun applyEdits() {
    val model = editModel() ?: return
    val source = source() ?: return
    val statements = model.pendingStatements()
    if (statements.isEmpty()) return
    val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
      project,
      t("db.edit.confirm.body", "count" to statements.size, "source" to source.name,
        "sql" to statements.joinToString("\n")),
      t("db.edit.confirm.title"),
      t("db.edit.confirm.yes"),
      t("db.confirmWrite.no"),
      AllIcons.General.WarningDialog,
    )
    if (answer != com.intellij.openapi.ui.Messages.YES) return
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("db.edit.applying", "count" to statements.size)
    val query = lastQuery
    val editable = editTarget
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = VibeDbService.getInstance(project)
      val connection = service.connect(source).getOrElse { error ->
        onUi { fail(t("db.connectFailed", "source" to DataSources.maskUrl(source.url), "reason" to (error.message ?: ""))) }
        return@executeOnPooledThread
      }
      val outcome = connection.use { service.applyAll(it, statements) }
      onUi {
        when (outcome) {
          is JdbcSession.Outcome.Failed -> fail(t("db.edit.failed", "reason" to outcome.message))
          else -> {
            model.discardEdits()
            refreshEditButtons()
            statusLine.text = t("db.edit.applied", "count" to statements.size)
            // Перечитываем: база могла изменить значение по-своему (тип, триггер, регистр), и
            // показывать набранное человеком вместо сохранённого — врать ему в глаза.
            if (editable != null) reRun(query, editable)
          }
        }
      }
    }
  }

  /** Повторяет запрос предпросмотра, сохраняя право на правку. */
  private fun reRun(sql: String, target: com.vibe.db.RowEdit.Target) {
    val table = target.table ?: return
    execute(sql, DbCatalog.Table(target.schema, table, DbCatalog.Kind.TABLE))
  }

  /** Позиция курсора в консоли; у редактора платформы она живёт не там, где у текстового поля. */
  private fun caretOffset(): Int = console.editor?.caretModel?.offset ?: console.text.length

  private companion object {
    /** Запас на отступы ячейки: без него текст упирается в границу столбца. */
    const val COLUMN_PADDING = 12

    /** Высота консоли: примерно пять строк, как было у текстового поля. */
    const val CONSOLE_HEIGHT = 110
  }

  private fun fail(text: String) {
    statusLine.foreground = JBColor.namedColor("Vibe.Db.error", JBColor(0xDB3B4B, 0xDB5C5C))
    statusLine.text = text
  }
}
