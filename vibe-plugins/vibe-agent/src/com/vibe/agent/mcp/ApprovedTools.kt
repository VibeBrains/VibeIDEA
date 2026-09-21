// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.ide.util.PropertiesComponent
import com.vibe.agent.providers.ToolSpec

/**
 * Набор инструментов MCP-сервера, на который человек согласился, — между запусками IDE.
 *
 * Хранится в настройках, а не в памяти: подмена описаний рассчитана на то, что её увидят нескоро
 * ([ToolFingerprint]), и защита, забывающая одобрение при перезапуске, отличалась бы от отсутствия
 * защиты только тем, что о ней написано в заметках.
 *
 * Ключ включает рабочую папку: один и тот же по имени сервер в разных проектах — разные серверы,
 * и согласие, данное в своём проекте, не должно распространяться на чужой клон.
 */
object ApprovedTools {
  /** Первое подключение согласия не требует: одобрять нечего, набор ещё никто не видел. */
  fun isFirstSight(project: String?, server: String): Boolean = read(project, server) == null

  /** Что изменилось с момента одобрения. Пустой дрейф — и когда всё совпало, и при первой встрече. */
  fun drift(project: String?, server: String, specs: List<ToolSpec>): ToolFingerprint.Drift {
    val approved = read(project, server) ?: return ToolFingerprint.Drift(emptyList(), emptyList(), emptyList())
    return ToolFingerprint.compare(approved, ToolFingerprint.map(specs))
  }

  /** Запомнить нынешний набор как одобренный. */
  fun approve(project: String?, server: String, specs: List<ToolSpec>) {
    val value = ToolFingerprint.map(specs).entries.joinToString(SEPARATOR) { "${it.key}=${it.value}" }
    PropertiesComponent.getInstance().setValue(key(project, server), value)
  }

  /** Забыть одобрение: сервер убрали из файла, и согласие на него не должно пережить возвращение. */
  fun forget(project: String?, server: String) {
    PropertiesComponent.getInstance().unsetValue(key(project, server))
  }

  private fun read(project: String?, server: String): Map<String, String>? {
    val raw = PropertiesComponent.getInstance().getValue(key(project, server)) ?: return null
    return raw.split(SEPARATOR).mapNotNull { pair ->
      val at = pair.lastIndexOf('=')
      if (at <= 0) null else pair.take(at) to pair.substring(at + 1)
    }.toMap()
  }

  private fun key(project: String?, server: String): String = "$PREFIX${project.orEmpty()}|$server"

  private const val PREFIX = "vibe.mcp.approvedTools."
  /** Перевод строки: в именах инструментов он невозможен, а запятая и точка с запятой — вполне. */
  private const val SEPARATOR = "\n"
}
