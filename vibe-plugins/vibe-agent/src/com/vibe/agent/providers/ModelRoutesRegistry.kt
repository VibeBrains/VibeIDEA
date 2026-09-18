// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * Таблица логических имён моделей, своя у каждого проекта.
 *
 * Ключ — корень проекта, ровно по тем же причинам, что у [ModelQuirksRegistry]: два открытых
 * проекта, у каждого своя `.vibe`, и общая переменная означала бы, что `@fast` в одном проекте
 * ведёт туда, куда его направил ДРУГОЙ. Отказ был бы тихим: запросы идут, просто не к той модели.
 */
object ModelRoutesRegistry {
  private const val NO_PROJECT = "<global>"

  private val byProject = ConcurrentHashMap<String, Map<String, String?>>()

  fun install(projectBase: String?, routes: Map<String, String?>) {
    byProject[projectBase ?: NO_PROJECT] = routes
  }

  fun of(projectBase: String?): Map<String, String?> = byProject[projectBase ?: NO_PROJECT].orEmpty()
}
