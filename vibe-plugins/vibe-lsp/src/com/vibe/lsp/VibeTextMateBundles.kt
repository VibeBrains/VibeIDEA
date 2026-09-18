// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * Подсветка для языков, которых в открытой платформе нет вовсе.
 *
 * Языковой сервер даёт СМЫСЛ — переходы, типы, подсказки; подсветку он не даёт, и без грамматики
 * `.vue` открывается «неизвестным файлом»: платформа предлагает выбрать тип вручную, а до тех пор
 * редактор чёрно-белый. Это же касается `.svelte`, `.astro`, а заодно `.sass` и `.styl`, которым мы
 * дали серверы 18.09.2026 и оставили без единого цвета (найдено чтением списка бандлов платформы в
 * тот же день — из наших диалектов там есть только `scss`).
 *
 * Регистрация — точкой расширения самой платформы (`com.intellij.textmate.bundleProvider`), а не
 * патчем: каталог бандлов платформы мы не трогаем, граница форка цела. Тип файла отдельно объявлять
 * не нужно — TextMate определяет его по расширению из `contributes.languages` бандла.
 *
 * Бандлы едут ФАЙЛАМИ рядом с плагином, а не ресурсами внутри jar: точка расширения принимает
 * [Path], а путь внутрь архива файловой системе неизвестен.
 */
class VibeTextMateBundles : TextMateBundleProvider {
  override fun getBundles(): List<TextMateBundleProvider.PluginBundle> {
    val root = bundlesRoot() ?: return emptyList()
    return NAMES.mapNotNull { name ->
      val path = root.resolve(name)
      // Отсутствующий бандл пропускается молча: в запуске из исходников каталог собирается
      // скриптом зависимостей, и падать здесь значило бы ронять весь плагин из-за подсветки.
      if (Files.isRegularFile(path.resolve("package.json"))) {
        TextMateBundleProvider.PluginBundle(name, path)
      } else {
        null
      }
    }
  }

  companion object {
    /**
     * Имена бандлов — они же имена каталогов, которые собирает `vibe-plugins/deps/download.sh`.
     *
     * Список повторён в гейте дистрибутива: бандл, потерянный при упаковке, снаружи неотличим от
     * бандла, которого мы не делали, — редактор просто чёрно-белый.
     */
    val NAMES: List<String> = listOf("vue", "svelte", "astro", "sass", "stylus")

    fun bundlesRoot(): Path? = ServerBinaries.textMateBundlesDir()
  }
}
