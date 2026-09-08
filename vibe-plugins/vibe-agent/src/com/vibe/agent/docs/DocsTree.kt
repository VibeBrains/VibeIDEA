// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

/**
 * Документы папками, как в дереве проекта.
 *
 * Плоский список отвечал на вопрос «какие файлы есть», но не на тот, который человек задаёт,
 * глядя на документацию: «как она устроена». В зрелом проекте документов три десятка, и список из
 * тридцати строк с путями внутри каждой — это то же самое дерево, только развернуть его надо в
 * голове. Ради этого узлы складываются в папки один раз здесь, а не в отрисовке.
 *
 * Папка-одиночка **склеивается с ребёнком** (`knowledge/ai` вместо `knowledge` → `ai`): цепочка
 * вложенных папок, в каждой из которых по одному входу, тратит уровни отступа и не сообщает
 * ничего — в дереве проекта это работает так же.
 *
 * Чистая: на вход пути и метки, на выход — узлы. Отрисовка ничего не решает, поэтому дерево можно
 * проверить, не поднимая интерфейс.
 */
object DocsTree {
  /**
   * Узел дерева. Лист несёт [path] документа; у папки он null, и по ней ничего не открывается.
   *
   * [problems] у папки — сумма по её поддереву: свёрнутая ветка обязана говорить, что внутри есть
   * на что посмотреть, иначе проблемы прячутся ровно там, где их не видно.
   */
  data class Node(
    val name: String,
    val path: String? = null,
    val title: String = "",
    val marks: List<String> = emptyList(),
    val children: List<Node> = emptyList(),
    val problems: Int = 0,
  ) {
    val isFolder: Boolean get() = path == null
  }

  /** Документ на входе: путь относительно корня проекта, заголовок и пометки («недостижим» и т.п.). */
  data class Item(val path: String, val title: String, val marks: List<String>)

  /**
   * @param root папка документации (`docs`), которую НЕ показываем узлом.
   *
   * Все документы лежат под ней по определению, поэтому единственный корневой узел «docs» тратил бы
   * уровень отступа и не сообщал ничего — а склеенный с ребёнком давал бы «docs/knowledge», где
   * первая половина одинакова у всех. Путь в листе при этом остаётся полным: по нему открывается
   * файл, и обрезать его значило бы чинить отображение за счёт работы.
   */
  fun build(items: List<Item>, root: String = ""): List<Node> {
    val prefix = root.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    return collapse(fold(prefix, items.filter { it.path.startsWith(prefix) }))
  }

  private fun fold(prefix: String, items: List<Item>): List<Node> {
    val here = ArrayList<Node>()
    val folders = LinkedHashMap<String, MutableList<Item>>()
    for (item in items) {
      val rest = item.path.removePrefix(prefix)
      val slash = rest.indexOf('/')
      if (slash < 0) here.add(Node(rest, item.path, item.title, item.marks, problems = item.marks.size))
      else folders.getOrPut(rest.substring(0, slash)) { ArrayList() }.add(item)
    }
    val nodes = folders.map { (name, inside) ->
      val children = fold("$prefix$name/", inside)
      Node(name = name, children = children, problems = children.sumOf { it.problems })
    }
    // Папки выше файлов, и те и другие по алфавиту — тот же порядок, что в дереве проекта: человек
    // ищет глазами по привычной раскладке, а не по порядку обхода файловой системы.
    return nodes.sortedBy { it.name.lowercase() } + here.sortedBy { it.name.lowercase() }
  }

  private fun collapse(nodes: List<Node>): List<Node> = nodes.map { node ->
    var current = node.copy(children = collapse(node.children))
    while (current.isFolder && current.children.size == 1 && current.children.first().isFolder) {
      val only = current.children.first()
      current = only.copy(name = current.name + "/" + only.name)
    }
    current
  }
}
