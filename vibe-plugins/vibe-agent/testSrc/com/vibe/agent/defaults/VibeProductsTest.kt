// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Адрес записи общего набора: кому она предназначена и что значит его отсутствие. */
class VibeProductsTest {
  private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

  @Test
  fun `поля нет — запись для всех`() {
    // Умолчание обязано совпадать с частым случаем: большинство записей универсальны, и
    // обязательное поле на каждой протухло бы в первый же месяц.
    assertTrue(VibeProducts.addressedToUs(obj("""{ "id": "x" }""")))
  }

  @Test
  fun `наш id в списке — наша запись, чужой — чужая`() {
    assertTrue(VibeProducts.addressedToUs(obj("""{ "products": ["vibeidea"] }""")))
    assertTrue(VibeProducts.addressedToUs(obj("""{ "products": ["vibeide", "vibeidea"] }""")))
    assertFalse(VibeProducts.addressedToUs(obj("""{ "products": ["vibeide"] }""")))
  }

  @Test
  fun `незнакомый продукт — мимо, а не ошибка`() {
    // Совместимость вперёд: сборка, выпущенная сегодня, увидит запись для продукта, которого
    // ещё нет, и промолчит. Иначе каждый новый продукт ломал бы все выпущенные сборки.
    assertFalse(VibeProducts.addressedToUs(obj("""{ "products": ["vibecli"] }""")))
    assertFalse(VibeProducts.addressedToUs(obj("""{ "products": ["продукт-из-2027"] }""")))
  }

  @Test
  fun `строка принимается наравне с массивом`() {
    // Человек рано или поздно напишет одно значение без скобок. Строгость здесь ничего не
    // защищает — она молча выключает его запись.
    assertTrue(VibeProducts.addressedToUs(obj("""{ "products": "vibeidea" }""")))
    assertFalse(VibeProducts.addressedToUs(obj("""{ "products": "vibeide" }""")))
  }

  @Test
  fun `мусор в адресе делает запись ничьей, а не общей`() {
    // Ошибиться в сторону «не выполнять чужое» дешевле, чем в сторону «выполнить чужую команду».
    assertFalse(VibeProducts.addressedToUs(obj("""{ "products": {} }""")))
    assertFalse(VibeProducts.addressedToUs(obj("""{ "products": [] }""")), "адрес в никуда — не адрес всем")
  }

  @Test
  fun `регистр значения не решает`() {
    assertTrue(VibeProducts.addressedToUs(obj("""{ "products": ["VibeIDEA"] }""")))
  }
}
