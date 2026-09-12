// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

/**
 * Разбор Ext JS без парсера JavaScript.
 *
 * В открытой платформе JS-ядра нет: `.js` открывается подсветкой TextMate, PSI у файла плоское, и
 * дерева, по которому можно было бы найти `Ext.define`, не существует. Поэтому здесь всё считается
 * по тексту — сознательное решение, а не упрощение: альтернативой был бы свой парсер JavaScript.
 *
 * Цена решения известна и принимается: имя класса, собранное из переменных, найдено не будет, а имя
 * внутри комментария будет. Для навигации это приемлемо — худшее, что случится, лишний вариант
 * перехода, а не испорченный файл.
 */

/** Объявление, к которому можно перейти: имя класса или псевдоним и место в файле. */
data class ExtSymbol(val name: String, val offset: Int)

/** Имя класса — точечная цепочка идентификаторов: `common.MorbusOnko.Modals.BaseModal`. */
private val CLASS_NAME = Regex("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$")

/** `Ext.define('имя'` — единственный способ объявить класс в Ext JS. */
private val DEFINE = Regex("""Ext\s*\.\s*define\s*\(\s*(['"])([^'"]+)\1""")

/**
 * `alias: 'widget.имя'` и `xtype: 'имя'` — по ним класс ищут из разметки, поэтому переход по ним
 * нужен ровно так же, как по имени класса.
 */
private val ALIAS = Regex("""\b(alias|xtype)\s*:\s*(['"])([^'"]+)\2""")

/** Все объявления файла: имена классов и псевдонимы, каждое со своим местом. */
fun scanSymbols(text: CharSequence): List<ExtSymbol> {
  val symbols = ArrayList<ExtSymbol>()
  DEFINE.findAll(text).forEach { match ->
    val group = match.groups[2] ?: return@forEach
    symbols.add(ExtSymbol(group.value, group.range.first))
  }
  ALIAS.findAll(text).forEach { match ->
    val group = match.groups[3] ?: return@forEach
    symbols.add(ExtSymbol(group.value, group.range.first))
  }
  return symbols
}

/** Считается ли текст именем класса Ext JS: без точки это переменная, а не класс. */
fun isExtClassName(text: String): Boolean = CLASS_NAME.matches(text)

/**
 * Содержимое строкового литерала под курсором.
 *
 * Кавычки ищутся в пределах строки текста: литерал в Ext JS не переносится, а поиск по всему файлу
 * на несбалансированной кавычке увёл бы курсор в соседнюю функцию.
 */
fun literalAt(text: CharSequence, offset: Int): String? {
  if (offset < 0 || offset > text.length) return null
  val lineStart = text.lastIndexOfChar('\n', offset - 1) + 1
  val lineEnd = text.indexOfChar('\n', offset).let { if (it < 0) text.length else it }

  var quoteStart = -1
  var quote = ' '
  var index = lineStart
  while (index < lineEnd) {
    val char = text[index]
    if (char == '\'' || char == '"') {
      if (quoteStart < 0) {
        quoteStart = index
        quote = char
      }
      else if (char == quote) {
        // Курсор на самой кавычке тоже считается попаданием: по краю выделения кликают часто.
        if (offset in quoteStart..index) return text.subSequence(quoteStart + 1, index).toString()
        quoteStart = -1
      }
    }
    index++
  }
  return null
}

private fun CharSequence.indexOfChar(char: Char, from: Int): Int {
  var index = from.coerceAtLeast(0)
  while (index < length) {
    if (this[index] == char) return index
    index++
  }
  return -1
}

private fun CharSequence.lastIndexOfChar(char: Char, from: Int): Int {
  var index = from.coerceAtMost(length - 1)
  while (index >= 0) {
    if (this[index] == char) return index
    index--
  }
  return -1
}
