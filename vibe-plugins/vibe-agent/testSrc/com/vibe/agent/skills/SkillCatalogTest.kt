// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillCatalogTest {
  private fun skill(name: String, description: String, body: String = "тело") =
    "---\nname: $name\ndescription: $description\n---\n\n$body"

  private val released = mapOf(
    "review-pr" to skill("review-pr", "обзор пул-реквеста"),
    "grill" to skill("grill", "допрос решения"),
  )

  @Test
  fun `нетронутая копия набора и правленая различаются`() {
    val installed = mapOf(
      "review-pr" to released.getValue("review-pr"),
      "grill" to skill("grill", "допрос решения", body = "моя правка"),
    )
    val items = SkillCatalog.build(installed, released).associateBy { it.id }
    assertEquals(SkillCatalog.State.PRISTINE, items.getValue("review-pr").state)
    assertEquals(SkillCatalog.State.EDITED, items.getValue("grill").state)
  }

  @Test
  fun `перевод строк правкой не считается`() {
    val installed = mapOf("review-pr" to released.getValue("review-pr").replace("\n", "\r\n"))
    assertEquals(SkillCatalog.State.PRISTINE, SkillCatalog.build(installed, released).first { it.id == "review-pr" }.state)
  }

  @Test
  fun `свой навык не из набора помечен своим`() {
    val installed = mapOf("наш-релиз" to skill("наш-релиз", "выкатка"))
    val item = SkillCatalog.build(installed, released).first { it.id == "наш-релиз" }
    assertEquals(SkillCatalog.State.OWN, item.state)
    assertFalse(SkillCatalog.canRevert(item), "чужого оригинала у своего навыка нет")
  }

  @Test
  fun `удалённый навык виден как удалённый, а не исчезает из каталога`() {
    val item = SkillCatalog.build(emptyMap(), released).first { it.id == "grill" }
    assertEquals(SkillCatalog.State.MISSING, item.state)
    assertEquals("допрос решения", item.description, "описание берём из набора — показать всё равно нужно")
  }

  @Test
  fun `вернуть можно только правленую копию поставляемого навыка`() {
    val installed = mapOf("grill" to skill("grill", "допрос решения", body = "правка"))
    val items = SkillCatalog.build(installed, released)
    assertTrue(SkillCatalog.canRevert(items.first { it.id == "grill" }))
    assertFalse(SkillCatalog.canRevert(items.first { it.id == "review-pr" }), "отсутствующий вернётся засевом сам")
  }

  @Test
  fun `версия и вердикт валидатора попадают в каталог`() {
    val installed = mapOf("grill" to released.getValue("grill"))
    val item = SkillCatalog.build(installed, released, versions = mapOf("grill" to 3), broken = setOf("grill")).single { it.id == "grill" }
    assertEquals(3, item.version)
    assertTrue(item.broken)
    assertEquals("grill", item.name)
  }
}
