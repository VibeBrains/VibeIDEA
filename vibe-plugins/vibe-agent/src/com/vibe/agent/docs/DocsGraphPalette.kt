// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import com.intellij.ui.JBColor

/**
 * Цвет категории документа — фиксированная палитра плюс устойчивый хеш имени.
 *
 * Категории появляются в рантайме: сегодня `knowledge` и `manuals`, завтра `adr` и `runbooks`.
 * Раздавать цвета по порядку обхода нельзя — тогда добавленная папка перекрашивает все остальные,
 * и человек, помнивший «фиолетовые — это знания», каждый день учит цвета заново. Хеш имени даёт
 * один и тот же цвет между запусками и не зависит от того, сколько папок рядом.
 *
 * **Отклонение от задания, названное вслух:** в задании сказано брать токены `charts.blue/green/…`
 * из темы редактора. В нашей платформе (263) таких ключей нет — проверено поиском по
 * `platform/`, включая `*.theme.json` и схемы редактора. Поэтому палитра объявлена своими токенами
 * `Vibe.Docs.category*` с осмысленными умолчаниями для светлой и тёмной тем: они так же
 * переопределяются темой пользователя, что и было целью требования.
 */
object DocsGraphPalette {
  /** Умолчания подобраны различимыми и в светлой, и в тёмной теме; порядок фиксирован. */
  private val PALETTE: List<JBColor> = listOf(
    JBColor.namedColor("Vibe.Docs.category1", JBColor(0x9C6ADE, 0xA47BE8)),
    JBColor.namedColor("Vibe.Docs.category2", JBColor(0x3574F0, 0x548AF7)),
    JBColor.namedColor("Vibe.Docs.category3", JBColor(0x1F9C6B, 0x4CB782)),
    JBColor.namedColor("Vibe.Docs.category4", JBColor(0xC27D04, 0xD6AE58)),
    JBColor.namedColor("Vibe.Docs.category5", JBColor(0x1E9AA8, 0x3FB6C4)),
    JBColor.namedColor("Vibe.Docs.category6", JBColor(0xC0468A, 0xD673A8)),
  )

  /** Корневые документы (категории нет) — первым цветом: их мало, и они всегда одни и те же. */
  fun colorOf(category: String): JBColor {
    if (category.isEmpty()) return PALETTE[1]
    return PALETTE[Math.floorMod(stableHash(category), PALETTE.size)]
  }

  /**
   * Устойчивый хеш имени.
   *
   * Свой, а не `String.hashCode()`: платформенный не обязан совпадать между версиями JVM, и цвет
   * категории однажды поменялся бы на ровном месте — без единой правки в проекте.
   */
  fun stableHash(text: String): Int {
    var hash = 2166136261u.toInt()
    for (ch in text) {
      hash = hash xor ch.code
      hash *= 16777619
    }
    return hash
  }
}
