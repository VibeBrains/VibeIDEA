// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import com.vibe.db.DataSources
import com.vibe.db.CsvExport
import com.vibe.db.DbCatalog
import com.vibe.db.DbSettings
import com.vibe.db.JdbcDrivers
import com.vibe.db.QueryLimit
import com.vibe.db.SqlStatements
import com.vibe.db.JdbcSession
import com.vibe.db.VibeDbService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Панель базы: слева объекты, справа консоль и результат.
 *
 * Раскладка знакомая по PhpStorm и DataGrip, потому что менять её незачем: дерево слева, запрос
 * сверху, таблица снизу — так на это смотрят двадцать лет.
 */
class DbPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val sources = ComboBox<DataSources.DataSource>().apply {
    renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.name }
    addActionListener { loadTree() }
  }
  private val search = JBTextField().apply {
    toolTipText = t("db.search.hint")
    document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = refreshTree()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = refreshTree()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = refreshTree()
    })
  }
  private val treeRoot = DefaultMutableTreeNode()
  private val treeModel = DefaultTreeModel(treeRoot)
  private val tree = Tree(treeModel).apply {
    isRootVisible = false
    addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        if (e.clickCount == 2) openSelectedTable()
      }
    })
  }
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

  /** Прочитанные столбцы по таблицам — чтобы не спрашивать метаданные на каждое раскрытие. */
  private val columnsCache = HashMap<DbCatalog.Table, List<DbCatalog.Column>>()

  /** Заглушка «ещё не читали» под таблицей: узел без детей не даёт стрелку раскрытия. */
  private object LOADING { override fun toString(): String = t("db.loadingColumns") }

  /** Схемы, как их вернула база: дерево строится из них, поиск только фильтрует показ. */
  private var schemas: List<DbCatalog.Schema> = emptyList()

  /** Последний показанный результат — его и выгружаем; выгружать «то, что в таблице» нечего иначе. */
  private var lastTable: com.vibe.db.ResultTable.Table? = null
  private var lastQuery: String = ""

  /**
   * Таблица, которую показывает результат, — и только она даёт право на правку.
   *
   * Правится то, что мы сами открыли двойным щелчком: там известны и таблица, и её ключ. Для
   * запроса, набранного руками, таблицу пришлось бы угадывать по тексту, а угаданный `UPDATE`
   * выполняется на настоящих данных.
   */
  private var editTarget: com.vibe.db.RowEdit.Target? = null

  /**
   * «Пароль…» — без этой кнопки пароль нельзя задать вовсе: в файле его нет по построению,
   * а связка ключей сама себя не заполнит. Функциональная дыра, найденная ревизией 03.09.2026.
   */
  private val passwordButton = JButton(t("db.password"), AllIcons.General.InspectionsEye).apply {
    addActionListener { askPassword() }
  }

  /**
   * «Скачать драйвер» — иначе первый шаг это задание по сборке: человек хотел посмотреть таблицу,
   * а должен найти jar в интернете. Качаем по явному нажатию, из Maven Central, с проверкой sha256.
   */
  private val driverButton = JButton(t("db.driver.download"), AllIcons.Actions.Download).apply {
    isVisible = false
    addActionListener { downloadDriver() }
  }

  private val exportButton = JButton(t("db.export"), AllIcons.ToolbarDecorator.Export).apply {
    isEnabled = false
    addActionListener { exportCsv() }
  }

  /**
   * «Применить правки» — единственный путь, которым правка ячейки попадает в базу.
   *
   * Кнопка появляется только когда правки есть: пустая кнопка «применить» на просмотре создаёт
   * впечатление, что таблица редактируема, там где она не редактируема.
   */
  private val applyButton = JButton(t("db.edit.apply"), AllIcons.Actions.Commit).apply {
    isVisible = false
    addActionListener { applyEdits() }
  }

  private val discardButton = JButton(t("db.edit.discard"), AllIcons.Actions.Rollback).apply {
    isVisible = false
    addActionListener { discardEdits() }
  }

  init {
    // Столбцы читаются по раскрытию узла: на базе с тысячей таблиц читать их сразу — тысяча
    // запросов к метаданным ради дерева, которое человек не разворачивал.
    tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
      override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) = Unit
      override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
        val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val table = node.userObject as? DbCatalog.Table ?: return
        if (columnsCache.containsKey(table)) return
        loadColumns(table, node)
      }
    })

    val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
      add(JBLabel(t("db.source")))
      add(sources)
      add(JButton(t("db.reload"), AllIcons.Actions.Refresh).apply { addActionListener { reload() } })
      add(passwordButton)
      add(driverButton)
    }
    val left = JPanel(BorderLayout()).apply {
      add(search, BorderLayout.NORTH)
      add(VibeScroll.pane(tree), BorderLayout.CENTER)
      preferredSize = Dimension(280, 0)
    }
    // Ctrl+Space — тот же жест, что и везде в IDE. Локальным шорткатом, а не KeyListener:
    // в редакторе платформы нажатия сначала проходят через её же диспетчер, и слушатель на
    // компоненте до них просто не доходит (тот же урок, что с меню «@» в композере).
    object : com.intellij.openapi.actionSystem.AnAction() {
      override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = suggestCompletion()
    }.registerCustomShortcutSet(
      com.intellij.openapi.actionSystem.CustomShortcutSet(
        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE, java.awt.event.InputEvent.CTRL_DOWN_MASK)),
      console,
    )
    val consolePanel = JPanel(BorderLayout()).apply {
      // Своей обёртки-скролла у редактора не нужно: он прокручивается сам, а вторая рамка дала бы
      // две полосы на одном поле.
      add(console, BorderLayout.CENTER)
      add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(JButton(t("db.run"), AllIcons.Actions.Execute).apply { addActionListener { runConsole() } })
        add(exportButton)
        add(applyButton)
        add(discardButton)
        add(JBLabel(t("db.run.hint")).apply { foreground = JBColor.GRAY })
      }, BorderLayout.SOUTH)
    }
    val right = OnePixelSplitter(true, 0.35f).apply {
      firstComponent = consolePanel
      secondComponent = JPanel(BorderLayout()).apply {
        add(statusLine, BorderLayout.NORTH)
        add(VibeScroll.pane(results), BorderLayout.CENTER)
      }
    }
    add(top, BorderLayout.NORTH)
    add(OnePixelSplitter(false, 0.28f).apply {
      firstComponent = left
      secondComponent = right
    }, BorderLayout.CENTER)
    reload()
  }

  /** Перечитывает `.vibe/dataSources.json` и обновляет список подключений. */
  fun reload() {
    val root = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
    val file = root?.resolve(DataSources.FILE)
    val text = file?.takeIf { Files.isRegularFile(it) }?.let { runCatching { Files.readString(it) }.getOrNull() }
    val parsed = DataSources.parse(text)
    sources.removeAllItems()
    parsed.sources.forEach(sources::addItem)
    statusLine.foreground = JBColor.foreground()
    statusLine.text = when {
      parsed.sources.isEmpty() -> t("db.noSources", "file" to DataSources.FILE)
      else -> t("db.sourcesFound", "count" to parsed.sources.size)
    }
    // Пароль в файле — находка, а не мелочь: он уже в репозитории, и сказать об этом надо сразу.
    // Остальные находки тоже называются: пропущенное подключение без объяснения выглядит как
    // «инструмент не видит мою базу».
    val password = parsed.problems.firstOrNull { it.trouble == DataSources.Trouble.PASSWORD_IN_FILE }
    val others = parsed.problems.filter { it.trouble != DataSources.Trouble.PASSWORD_IN_FILE }
    if (password != null) {
      statusLine.foreground = JBColor.namedColor("Vibe.Db.error", JBColor(0xDB3B4B, 0xDB5C5C))
      statusLine.text = t("db.passwordInFile", "source" to password.where, "file" to DataSources.FILE)
    }
    else if (others.isNotEmpty()) {
      statusLine.foreground = JBColor.namedColor("Vibe.Db.error", JBColor(0xDB3B4B, 0xDB5C5C))
      statusLine.text = t("db.sourceProblems", "problems" to others.joinToString("; ") { problem ->
        val what = when (problem.trouble) {
          DataSources.Trouble.NOT_AN_OBJECT -> t("db.problem.notAnObject")
          DataSources.Trouble.NO_ID -> t("db.problem.noId")
          DataSources.Trouble.NO_URL -> t("db.problem.noUrl")
          DataSources.Trouble.DUPLICATE_ID -> t("db.problem.duplicateId")
          DataSources.Trouble.PASSWORD_IN_FILE -> t("db.problem.password")
        }
        t("db.problem.at", "where" to problem.where, "what" to what)
      })
    }
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

  private fun source(): DataSources.DataSource? = sources.selectedItem as? DataSources.DataSource

  /** Кнопка показывается, только если драйвер для этой базы известен и ещё не скачан. */
  private fun refreshDriverButton() {
    val source = source()
    val driver = source?.let { JdbcDrivers.forUrl(it.url) }
    val home = System.getProperty("user.home")
    val needed = driver != null && source.driverPath.isNullOrBlank() &&
                 (home == null || !JdbcDrivers.isDownloaded(home, driver))
    driverButton.isVisible = needed
    driverButton.text = if (driver == null) t("db.driver.download")
    else t("db.driver.downloadNamed", "title" to driver.title, "version" to driver.version, "licence" to driver.licence)
  }

  private fun downloadDriver() {
    val source = source() ?: return
    val driver = JdbcDrivers.forUrl(source.url) ?: return
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("db.driver.downloading", "title" to driver.title, "size" to (driver.sizeBytes / 1024 / 1024))
    ApplicationManager.getApplication().executeOnPooledThread {
      val outcome = VibeDbService.getInstance(project).download(driver)
      onUi {
        when (outcome) {
          is VibeDbService.Download.Done -> {
            statusLine.text = t("db.driver.downloaded", "path" to outcome.path.toString(), "file" to DataSources.FILE)
            refreshDriverButton()
          }
          // Не совпал хеш — это не «сеть подвела», об этом надо сказать отдельно и громко.
          VibeDbService.Download.HashMismatch -> fail(t("db.driver.hashMismatch", "title" to driver.title))
          is VibeDbService.Download.Failed -> fail(t("db.driver.failed", "reason" to outcome.message))
        }
      }
    }
  }

  /**
   * Спрашивает пароль и кладёт его в связку ключей системы.
   *
   * Пустой ввод удаляет сохранённый: «оставить как было» и «убрать» — разные желания, и молча
   * приравнивать первое ко второму значит однажды не дать человеку отозвать пароль.
   */
  private fun askPassword() {
    val source = source() ?: return
    val service = VibeDbService.getInstance(project)
    val stored = service.password(source)
    val entered = com.intellij.openapi.ui.Messages.showPasswordDialog(
      project,
      t("db.password.prompt", "source" to source.name, "user" to (source.user ?: "—")),
      t("db.password.title"),
      null,
    ) ?: return
    service.storePassword(source, entered.takeIf { it.isNotEmpty() })
    statusLine.foreground = JBColor.foreground()
    statusLine.text = when {
      entered.isEmpty() && stored != null -> t("db.password.removed", "source" to source.name)
      entered.isEmpty() -> t("db.password.none", "source" to source.name)
      else -> t("db.password.saved", "source" to source.name)
    }
  }

  private fun loadTree() {
    refreshDriverButton()
    val source = source() ?: return
    schemas = emptyList()
    refreshTree()
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = VibeDbService.getInstance(project)
      val connection = service.connect(source).getOrElse { error ->
        onUi { fail(t("db.connectFailed", "source" to DataSources.maskUrl(source.url), "reason" to (error.message ?: ""))) }
        return@executeOnPooledThread
      }
      val tables = runCatching { connection.use { service.tables(it) } }.getOrElse { error ->
        onUi { fail(t("db.metaFailed", "reason" to (error.message ?: ""))) }
        return@executeOnPooledThread
      }
      val grouped = DbCatalog.group(tables, DbSettings.showSystemSchemas)
      onUi {
        schemas = grouped
        refreshTree()
        statusLine.foreground = JBColor.foreground()
        statusLine.text = t("db.tablesFound", "count" to tables.size)
      }
    }
  }

  private fun loadColumns(table: DbCatalog.Table, node: DefaultMutableTreeNode) {
    val source = source() ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = VibeDbService.getInstance(project)
      val connection = service.connect(source).getOrNull() ?: return@executeOnPooledThread
      val columns = runCatching { connection.use { service.columns(it, table) } }.getOrDefault(emptyList())
      onUi {
        columnsCache[table] = columns
        node.removeAllChildren()
        columns.forEach { node.add(DefaultMutableTreeNode(it)) }
        treeModel.nodeStructureChanged(node)
      }
    }
  }

  private fun refreshTree() {
    treeRoot.removeAllChildren()
    for (schema in DbCatalog.filter(schemas, search.text)) {
      val node = DefaultMutableTreeNode(schema.name.ifEmpty { t("db.noSchema") })
      schema.tables.forEach { table ->
        val tableNode = DefaultMutableTreeNode(table)
        // Столбцы подгружаются по раскрытию узла, а не сразу для всех таблиц: на базе с тысячей
        // таблиц это тысяча запросов к метаданным ради дерева, которое человек не разворачивал.
        columnsCache[table]?.forEach { tableNode.add(DefaultMutableTreeNode(it)) }
          ?: tableNode.add(DefaultMutableTreeNode(LOADING))
        node.add(tableNode)
      }
      treeRoot.add(node)
    }
    treeModel.reload()
    for (row in 0 until tree.rowCount) tree.expandRow(row)
  }

  private fun openSelectedTable() {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
    val table = node.userObject as? DbCatalog.Table ?: return
    val sql = QueryLimit.preview(table.name, table.schema, DbSettings.previewRows)
    console.text = sql
    execute(sql, table)
  }

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
    val selected = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DbCatalog.Table
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
    val suggestions = com.vibe.db.SqlCompletion.suggest(console.text, caret, schemas) { table ->
      columnsCache[table] ?: emptyList()
    }
    if (suggestions.isEmpty()) {
      // Молчать нельзя: нажатие без ответа читается как «подсказок нет в принципе».
      statusLine.foreground = JBColor.foreground()
      statusLine.text = if (schemas.isEmpty()) t("db.completion.noSchema") else t("db.completion.nothing")
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
    val mentioned = com.vibe.db.SqlCompletion.visibleTables(console.text, schemas).filterNot { columnsCache.containsKey(it) }
    if (mentioned.isEmpty()) return
    val source = source() ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = VibeDbService.getInstance(project)
      val connection = service.connect(source).getOrNull() ?: return@executeOnPooledThread
      val loaded = connection.use { open -> mentioned.associateWith { service.columns(open, it) } }
      onUi { columnsCache.putAll(loaded) }
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
