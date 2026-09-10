// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Кому адресована запись общего набора сидов.
 *
 * Набор `.vibe` пишут несколько продуктов сразу (VibeIDE, VibeIDEA, дальше VibeCLI), и не всякая
 * запись имеет смысл во всяком из них. Поле `active` этот вопрос не решает: «кому адресовано» и
 * «включено ли» — разные вопросы, и запись бывает чужой и включённой у соседа. Поэтому адрес
 * едет отдельным полем `products`.
 *
 * Правила, согласованные с VibeIDE 09.09.2026:
 *  • поля нет — запись для ВСЕХ (подавляющее большинство записей универсальны, и обязательное
 *    поле на каждой протухло бы в первый же месяц);
 *  • запись не для нас пропускается МОЛЧА — это не проблема и не предупреждение;
 *  • незнакомый id — тоже молча мимо: сборка, выпущенная сегодня, увидит запись для продукта,
 *    которого ещё нет, и промолчит.
 *
 * Чего поле НЕ даёт: совместимости назад. Сборка, которая о `products` не знает, поле игнорирует
 * и запись ВЫПОЛНЯЕТ. Поэтому первая адресная запись едет в набор только после того, как читатель
 * разъехался по всем продуктам, а до тех пор адресная запись обязана быть выключенной.
 */
object VibeProducts {
  /** Стабильный id этого продукта: нижний регистр, не отображаемое имя — имена меняются, id нет. */
  const val THIS = "vibeidea"

  // Своей константы со списком продуктов здесь НЕТ и быть не должно. Словарь общий и живёт в
  // наборе (`products.json`) — читается через `VibeDefaults.knownProducts()`. Константа у каждого
  // продукта разошлась бы с чужой молча: та же механика, что сломала цену под `cost*`, только
  // вместо имени поля имя продукта. Довод VibeIDE 10.09.2026, принят целиком.

  /** Адресована ли запись нам. */
  fun addressedToUs(obj: JsonObject): Boolean = addressedTo(obj, THIS)

  /** Та же логика с явным продуктом — для тестов и для разбора чужой стороны. */
  fun addressedTo(obj: JsonObject, product: String): Boolean {
    val declared = declaredProducts(obj) ?: return true
    return declared.any { it.equals(product, ignoreCase = true) }
  }

  /**
   * Список продуктов записи, или null, если адрес не указан (значит — всем).
   *
   * Строка принимается наравне с массивом: `"products": "vibeidea"` человек напишет рано или
   * поздно, а строгость здесь ничего не защищает — только молча выключает его запись.
   * Значение непонятной формы адресом не считается и делает запись ничьей: ошибиться в сторону
   * «не выполнять чужое» дешевле, чем в сторону «выполнить чужую команду», а сам мусор поймает
   * тест набора.
   */
  fun declaredProducts(obj: JsonObject): List<String>? = when (val el = obj["products"]) {
    null -> null
    is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(el.contentOrNull)
    else -> emptyList()
  }
}
