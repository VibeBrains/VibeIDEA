// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.intellij.util.io.IntInlineKeyDescriptor
import com.intellij.util.io.DataExternalizer

/**
 * Где объявлен класс Ext JS: имя (или псевдоним) → место в файле.
 *
 * Индекс, а не поиск по проекту при каждом клике: у владельца проект медицинской системы, где таких
 * объявлений тысячи, и перебор файлов на каждом переходе был бы заметен рукам.
 */
object ExtDefineIndex : FileBasedIndexExtension<String, Int>() {
  val NAME: ID<String, Int> = ID.create("com.vibe.lsp.extjs.definitions")

  /** Версия поднимается при любой правке разбора: иначе останется индекс, собранный старым кодом. */
  private const val VERSION = 1

  override fun getName(): ID<String, Int> = NAME

  override fun getIndexer(): DataIndexer<String, Int, FileContent> = DataIndexer { content ->
    scanSymbols(content.contentAsText).associate { it.name to it.offset }
  }

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer(): DataExternalizer<Int> = IntInlineKeyDescriptor()

  override fun getVersion(): Int = VERSION

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter = ExtInputFilter()
}

/**
 * Индексируются только файлы с расширением JavaScript.
 *
 * Проверка идёт по имени, а не по типу файла: в открытой платформе `.js` достаётся то TextMate, то
 * простому тексту, и привязка к типу разошлась бы с реальностью на первой же настройке владельца.
 */
private class ExtInputFilter : FileBasedIndex.InputFilter {
  override fun acceptInput(file: VirtualFile): Boolean = file.extension == "js"
}
