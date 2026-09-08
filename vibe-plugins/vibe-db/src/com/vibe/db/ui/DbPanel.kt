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
  private val statusLine = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }

  /** Прочитанные столбцы по таблицам — чтобы не спрашивать метаданные на каждое раскрытие. */
  private val columnsCache = HashMap<DbCatalog.Table, List<DbCatalog.Column>>()

  /** Заглушка «ещё не читали» под таблицей: узел без детей не даёт стрелку раскрытия. */
  private object LOADING { override fun toString(): String = t("db.loadingColumns") }

  /** Схемы, как их вернула база: дерево строится из них, поиск только фильтрует показ. */
  private var schemas: List<DbCatalog.Schema> = emptyList()

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
    add(top, BorderLayout.NORTH)
    add(JPanel(BorderLayout()).apply {
      add(search, BorderLayout.NORTH)
      add(VibeScroll.pane(tree), BorderLayout.CENTER)
    }, BorderLayout.CENTER)
    add(statusLine, BorderLayout.SOUTH)
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

  private fun source(): DataSources.DataSource? = (sources.selectedItem as? DataSources.DataSource)
    .also { DbWorkbench.getInstance(project).source = it }

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
        // Схемы нужны подсказке в консоли — кладём их туда, где их видят обе половины.
        DbWorkbench.getInstance(project).schemas = grouped
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
        DbWorkbench.getInstance(project).columns[table] = columns
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

  /**
   * Двойной щелчок по таблице открывает её в ЦЕНТРАЛЬНОЙ панели.
   *
   * Панель слева отвечает на вопрос «что есть в базе», а данные читают, правят и выгружают в
   * центре: в колонке шириной с дерево проекта грид на двадцать столбцов бесполезен.
   */
  private fun openSelectedTable() {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
    val table = node.userObject as? DbCatalog.Table ?: return
    DbWorkbench.getInstance(project).showTable(table)
  }

  private fun fail(text: String) {
    statusLine.foreground = JBColor.namedColor("Vibe.Db.error", JBColor(0xDB3B4B, 0xDB5C5C))
    statusLine.text = text
  }
}
