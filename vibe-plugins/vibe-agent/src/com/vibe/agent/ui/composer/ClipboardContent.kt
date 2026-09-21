// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.ide.dnd.FileCopyPasteUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * Что в буфере обмена и что с этим делать — одно решение на все входы.
 *
 * Входов у вставки ДВА: обработчик передачи данных Swing (`TransferHandler`, он же обслуживает
 * перетаскивание) и действие вставки самой IDE (`$Paste` через `PasteProvider`). Пока решение жило
 * только в первом, картинка терялась всюду, где до него не доходило, — а снаружи это выглядело
 * как «вставка не работает» без единого сообщения (владелец, 21.09.2026).
 *
 * Поэтому решение вынесено сюда чистой функцией: оба входа спрашивают её, и разойтись в поведении
 * им негде. Заодно оно стало измеримым без окна, буфера и клавиатуры.
 */
object ClipboardContent {
  /** Что несёт буфер. Порядок разбора — от самого конкретного к самому общему. */
  sealed interface Kind {
    /** Файлы: картинки станут вложениями, PDF — текстом, остальное — ссылками на файлы проекта. */
    data class Files(val files: List<File>) : Kind

    /** Готовая картинка (скриншот, копирование из браузера). */
    data class Picture(val image: ImageAttachment) : Kind

    /** Ничего нашего: обычная текстовая вставка, её делает поле ввода. */
    data object Text : Kind
  }

  /**
   * Разобрать содержимое буфера.
   *
   * Правило приоритета названо здесь один раз: **картинка сильнее текста рядом с ней**. Прежде
   * стояло обратное условие — картинка принималась, только если текста в буфере НЕТ, — и это
   * ломало ровно те случаи, где картинку копируют чаще всего: браузер и мессенджер кладут рядом
   * с картинкой её адрес или подпись. Человек, копирующий картинку, хочет картинку; служебный
   * текст рядом — не выбор, а особенность источника.
   */
  fun of(transferable: Transferable?): Kind {
    if (transferable == null) return Kind.Text
    if (runCatching { FileCopyPasteUtil.isFileListFlavorAvailable(transferable.transferDataFlavors) }.getOrDefault(false)) {
      val files = runCatching { FileCopyPasteUtil.getFileList(transferable).orEmpty() }.getOrDefault(emptyList())
      if (files.isNotEmpty()) return Kind.Files(files)
    }
    if (runCatching { transferable.isDataFlavorSupported(DataFlavor.imageFlavor) }.getOrDefault(false)) {
      // Разбор может не удаться (чужой формат, битые данные): тогда честнее отдать вставку тексту,
      // чем проглотить нажатие и не показать ничего.
      Attachments.fromTransferable(transferable)?.let { return Kind.Picture(it) }
    }
    return Kind.Text
  }

  /**
   * Похоже ли содержимое на наше — ПО ВИДАМ ДАННЫХ, без чтения самих данных.
   *
   * Дешёвая проверка нужна отдельно от [of]: её зовут на каждое движение мыши при перетаскивании
   * и на каждое обновление действия вставки, а [of] ради ответа собирает PNG. Ошибиться в плюс
   * здесь безопасно: разбор всё равно случится, и не удавшийся вернёт вставку тексту.
   */
  fun carriesOurs(transferable: Transferable?): Boolean {
    if (transferable == null) return false
    return runCatching {
      transferable.isDataFlavorSupported(DataFlavor.imageFlavor) ||
      FileCopyPasteUtil.isFileListFlavorAvailable(transferable.transferDataFlavors)
    }.getOrDefault(false)
  }
}
