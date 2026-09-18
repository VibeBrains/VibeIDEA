// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Что агент может узнать о САМОЙ IDE, а не о проекте в ней.
 *
 * Разрыв, который это закрывает: у агента были граф импортов, корпус и журнал решений — то есть всё
 * про репозиторий, и ничего про инструмент, в котором он работает. На вопрос «какие настройки у
 * меня есть, чтобы подтянуть Angular» модель честно отвечала, что не знает, какие плагины и
 * языковые серверы подключены, и «любой совет с моей стороны был бы фантазией» (поймано владельцем
 * 18.09.2026). Совет про настройки IDE, данный вслепую, тратит время человека дважды: сперва на
 * выполнение, потом на выяснение, почему не помогло.
 *
 * Точка расширения, а не один класс со всеми знаниями: `vibe-lsp` зависит от нас, и обратная
 * зависимость была бы циклом. Свои факты рассказывает тот, кто ими владеет, — плагин языковых
 * серверов про серверы, плагин базы про подключения.
 */
interface VibeIdeFacts {
  /**
   * Несколько строк о своей области, или null — если рассказывать нечего.
   *
   * Строки, а не поля: ответ читает модель, и человеческая строка «сервер TypeScript: vtsls,
   * запущен» понятнее ей, чем наша схема, которой она нигде не видела.
   */
  fun facts(project: Project): List<String>

  companion object {
    val EP: ExtensionPointName<VibeIdeFacts> = ExtensionPointName.create("com.vibe.agent.ideFacts")

    /**
     * Полный рассказ об IDE: наша часть плюс всё, что добавили соседние плагины.
     *
     * Падение одного рассказчика не отменяет остальных: инструмент, который молчит целиком из-за
     * чужой ошибки, хуже неполного ответа — модель тогда считает, что узнать нечего.
     */
    fun collect(project: Project): List<String> {
      val lines = ArrayList<String>()
      lines += core()
      lines += plugins()
      for (source in EP.extensionList) {
        try {
          lines += source.facts(project)
        }
        catch (e: Exception) {
          lines += "не удалось спросить ${source.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
      }
      return lines
    }

    private fun core(): List<String> {
      val info = ApplicationInfo.getInstance()
      return listOf(
        "IDE: ${info.fullApplicationName}, сборка ${info.build.asString()}",
        "Платформа: ${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.arch").orEmpty()}, " +
        "JVM ${System.getProperty("java.version").orEmpty()}",
      )
    }

    /**
     * Плагины, которые что-то добавляют к платформе.
     *
     * Встроенные платформенные не перечисляются: их полторы сотни, они одинаковы во всех сборках, и
     * список из них вытеснит из ответа ровно то, ради чего его спрашивали. Наши `vibe-*` названы
     * всегда — это и есть отличие этой IDE от чужой.
     */
    private fun plugins(): List<String> {
      val enabled = PluginManagerCore.loadedPlugins.filter { it.isEnabled }
      val ours = enabled.filter { it.pluginId.idString.startsWith(OUR_PREFIX) }
      val ourIds = ours.mapTo(HashSet()) { it.pluginId.idString }
      val others = enabled.filter { !it.isBundled && it.pluginId.idString !in ourIds }
      return listOf(
        "Наши плагины: " + ours.joinToString { it.name + " " + it.version }.ifEmpty { "нет" },
        "Сторонние плагины: " + others.joinToString { it.name + " " + it.version }.ifEmpty { "нет" },
      )
    }

    private const val OUR_PREFIX = "com.vibe."
  }
}
