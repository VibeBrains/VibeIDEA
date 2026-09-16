// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.ide.util.PropertiesComponent

/**
 * Какая сессия внешнего агента была открыта в прошлый раз — чтобы её можно было возобновить.
 *
 * Наши треды переживают перезапуск IDE с первого дня: разговор виден в ленте целиком. А сессия
 * агента жила ровно столько, сколько его процесс, и после перезапуска он не помнил ничего — то
 * есть человек видел свой вопрос и чужой ответ на экране и пересказывал их заново, платя за
 * контекст второй раз. `session/resume` (стабилизирован в ACP v1 06.07.2026) это закрывает, но
 * ему нужен идентификатор, а хранить его было негде.
 *
 * Ключ — агент И тред: у каждого треда своя сессия, и один и тот же агент в двух тредах ведёт два
 * разных разговора. Подсунуть треду чужую сессию хуже, чем не возобновлять вовсе. До 16.09.2026
 * ключом была рабочая папка — сессия одна на панель; такая запись переезжает к первому треду,
 * который спросит, и больше не находится ни одним.
 *
 * Живёт на уровне приложения, а не в `.vibe`: это состояние ЭТОЙ машины, оно не имеет смысла в
 * чужом клоне репозитория и не должно появляться в диффе.
 */
object AcpSessionMemory {
  private const val PREFIX = "vibe.acp.session."
  private const val THREAD_PREFIX = PREFIX + "thread."

  /** NUL cannot occur in a path, an id or a name, so it separates them unambiguously. Written as an escape: a raw NUL byte made the source a «binary» file to grep. */
  private const val SEPARATOR = "\u0000"

  /**
   * Agents whose name changed: new name → old one. The name is part of the key, so without this a renamed agent
   * would lose the session remembered under its old name. «Claude Code» → «Claude Agent», decision №94.
   */
  private val RENAMED: Map<String, String> = mapOf("Claude Agent" to "Claude Code")

  internal fun key(agentName: String, threadId: String): String =
    THREAD_PREFIX + (threadId + SEPARATOR + agentName).hashCode().toString(16)

  /** The key before sessions were per thread: one per agent and working folder. Read only to move it. */
  internal fun legacyKey(agentName: String, projectBase: String?): String =
    PREFIX + (projectBase.orEmpty() + SEPARATOR + agentName).hashCode().toString(16)

  fun remember(agentName: String, threadId: String, sessionId: String) {
    PropertiesComponent.getInstance().setValue(key(agentName, threadId), sessionId)
  }

  fun recall(agentName: String, threadId: String, projectBase: String?): String? {
    val properties = PropertiesComponent.getInstance()
    return recall(agentName, threadId, projectBase, { properties.getValue(it) }, { k, v -> properties.setValue(k, v) }, { properties.unsetValue(it) })
  }

  /**
   * The thread's remembered session; failing that, one kept under the old per-folder key — under the agent's name or
   * its old name — moved to this thread on first use, so it is found by one thread and not by all of them.
   * Pure over the storage so the migration is testable without the platform.
   */
  internal fun recall(
    agentName: String,
    threadId: String,
    projectBase: String?,
    get: (String) -> String?,
    set: (String, String) -> Unit,
    unset: (String) -> Unit,
  ): String? {
    get(key(agentName, threadId))?.takeIf { it.isNotBlank() }?.let { return it }
    for (name in listOfNotNull(agentName, RENAMED[agentName])) {
      val oldKey = legacyKey(name, projectBase)
      val moved = get(oldKey)?.takeIf { it.isNotBlank() } ?: continue
      set(key(agentName, threadId), moved)
      unset(oldKey)
      return moved
    }
    return null
  }

  // Функции «забыть» здесь намеренно нет. Протухший идентификатор не требует уборки: агент
  // отказывает в возобновлении, клиент молча открывает новую сессию и записывает её номер поверх
  // старого. Отдельная кнопка забвения была бы состоянием, за которым надо следить, ради случая,
  // который уже закрыт откатом.
}
