// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.vibe.db.DataSources
import com.vibe.db.DbCatalog

/**
 * Общее состояние двух половин работы с базой: навигатора слева и консоли в центре.
 *
 * Концепция владельца 08.09.2026: **сбоку — действия и навигация, в центре — результат и работа с
 * ним**. Раскладка перестала быть одной панелью, а значит появилось то, что обе половины обязаны
 * знать одинаково: какое подключение выбрано и что уже прочитано о его схемах.
 *
 * Почему сервис, а не ссылка друг на друга: панель слева переживает закрытие вкладки в центре и
 * наоборот. Прямая ссылка означала бы, что закрытая вкладка уносит с собой выбранное подключение —
 * и человек, вернувшись, находит пустой список без объяснений.
 *
 * Кеш схем и столбцов живёт здесь по той же причине: читает их дерево, а нужны они подсказке в
 * консоли. Две копии кеша разошлись бы, и подсказка знала бы не то, что показано в дереве.
 */
@Service(Service.Level.PROJECT)
class DbWorkbench(private val project: Project) {
  /** Выбранное подключение; его задаёт навигатор, читает консоль. */
  @Volatile var source: DataSources.DataSource? = null

  /** Схемы и таблицы, прочитанные деревом. Пусто — дерево ещё не читало. */
  @Volatile var schemas: List<DbCatalog.Schema> = emptyList()

  /** Столбцы по таблицам: заполняет дерево при раскрытии, дочитывает подсказка. */
  val columns: MutableMap<DbCatalog.Table, List<DbCatalog.Column>> = java.util.concurrent.ConcurrentHashMap()

  /** Открытая консоль, если она есть: через неё навигатор просит показать таблицу. */
  @Volatile var console: DbConsolePanel? = null

  /**
   * Открывает (или выводит вперёд) вкладку консоли в центральной панели.
   *
   * Файл переиспользуется, а не создаётся заново: вторая вкладка «Консоль БД» рядом с первой —
   * это две истории запросов, между которыми человек будет искать свою.
   */
  fun openConsole(): DbConsolePanel? {
    val manager = FileEditorManager.getInstance(project)
    val file = manager.openFiles.firstOrNull { it is DbConsoleVirtualFile } ?: DbConsoleVirtualFile()
    manager.openFile(file, true)
    return console
  }

  /** Показать таблицу: открыть консоль и выполнить в ней предпросмотр. */
  fun showTable(table: DbCatalog.Table) {
    openConsole()?.showTable(table)
  }

  companion object {
    fun getInstance(project: Project): DbWorkbench = project.service()
  }
}
