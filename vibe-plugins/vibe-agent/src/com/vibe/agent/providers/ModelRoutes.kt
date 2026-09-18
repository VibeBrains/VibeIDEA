// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Логическое имя модели: `@fast` вместо `minimax/MiniMax-M3`, записанного в семи местах.
 *
 * Зачем. Шаг пайплайна, правило и роль называют модель адресом «провайдер/модель». Смена дешёвой
 * модели на другую дешёвую — это правка каждого места, где адрес переписан руками, и пропущенное
 * место молча продолжает платить прежнему вендору. Логическое имя разрывает эту связь: в файлах
 * стоит «какая модель нужна по смыслу», а куда именно она ведёт — решает одна таблица.
 *
 * Формат — блок `routes` в любом файле `.vibe/providers/`:
 *
 * ```jsonc
 * "routes": {
 *   "fast":  "minimax/MiniMax-M3",
 *   "smart": "anthropic/claude-opus-5",
 *   "vision": null            // имя объявлено и ЗАПРЕЩЕНО: см. ниже
 * }
 * ```
 *
 * Две вещи взяты у AIP-57 (`@agentproto/model-routing`, MIT), потому что они решают ровно те же
 * две беды:
 *
 * 1. **Слои.** Таблицы складываются в порядке чтения файлов: глобальная `~/.vibe`, затем проектная.
 *    Проект перекрывает глобальное имя, а не спорит с ним.
 * 2. **`null` — это запрет, а не пустое место.** Имя со значением `null` объявлено и закрыто:
 *    нижний слой НЕ может его молча вернуть. Иначе «выключил дорогую модель в проекте» означало бы
 *    «проект берёт её из глобального файла».
 *
 * Чистый: карты на входе, решение на выходе. Ни файлов, ни настроек.
 */
object ModelRoutes {
  /** Имя, начинающееся с этого знака, — ссылка на маршрут, а не адрес модели. */
  const val PREFIX = '@'

  sealed interface Resolution {
    /** Имя разрешилось в адрес. */
    data class Found(val provider: String, val model: String) : Resolution

    /** Имя объявлено со значением `null` — верхний слой закрыл его намеренно. */
    data object Disabled : Resolution

    /** Такого имени нет ни в одном слое; [known] — что есть, для сообщения человеку. */
    data class Unknown(val known: List<String>) : Resolution

    /** Имя есть, но записано не адресом «провайдер/модель». */
    data class Malformed(val value: String) : Resolution
  }

  fun isReference(value: String?): Boolean =
    value != null && value.trim().length > 1 && value.trim()[0] == PREFIX

  /** Имя без знака и без пробелов по краям. */
  fun nameOf(reference: String): String = reference.trim().removePrefix(PREFIX.toString()).trim()

  /**
   * Слои в порядке чтения: первый — самый слабый.
   *
   * Объявленный `null` переживает слияние как значение, а не как отсутствие ключа: именно он и
   * означает запрет.
   */
  fun merge(layers: List<Map<String, String?>>): Map<String, String?> {
    val merged = LinkedHashMap<String, String?>()
    for (layer in layers) for ((name, target) in layer) merged[name.trim()] = target
    return merged
  }

  fun resolve(reference: String, routes: Map<String, String?>): Resolution {
    val name = nameOf(reference)
    if (!routes.containsKey(name)) return Resolution.Unknown(routes.keys.sorted())
    val target = routes[name] ?: return Resolution.Disabled
    val slash = target.indexOf('/')
    if (slash <= 0 || slash >= target.length - 1) return Resolution.Malformed(target)
    return Resolution.Found(target.substring(0, slash).trim(), target.substring(slash + 1).trim())
  }
}
