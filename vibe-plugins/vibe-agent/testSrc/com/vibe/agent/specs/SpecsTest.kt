// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.specs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecPackageTest {
  private val labels = object : SpecPackage.Labels {
    override val noProduct = "нет PRODUCT.md"
    override val noTech = "нет TECH.md"
    override val noInvariants = "нет нумерованных инвариантов"
    override val notGrounded = "не заземлена в коде"
    override val productHeader = "что должно быть правдой"
    override val techHeader = "как это сделано"
    override val techTitle = "реализация"
  }

  @Test
  fun `a missing file is an error, a weak file is a warning`() {
    // На черновик не кричат: иначе черновик начнут писать не здесь.
    val missing = SpecPackage.validate(SpecPackage.Spec("feature", null, null), labels)
    assertEquals(2, missing.count { it.level == SpecPackage.Level.ERROR })
    val weak = SpecPackage.validate(SpecPackage.Spec("feature", "просто текст", "просто текст"), labels)
    assertEquals(2, weak.count { it.level == SpecPackage.Level.WARNING })
  }

  @Test
  fun `numbered invariants satisfy the product spec`() {
    val spec = SpecPackage.Spec("f", "1. Кнопка видна всегда\n2. Нажатие отменяемо", "см. src/App.kt")
    assertTrue(SpecPackage.validate(spec, labels).isEmpty())
  }

  @Test
  fun `a tech spec naming no file is a wish`() {
    // Такая спека протухает за неделю: с существующим кодом её ничто не связывает.
    val spec = SpecPackage.Spec("f", "1. Инвариант", "Сделаем красиво и быстро")
    assertEquals(1, SpecPackage.validate(spec, labels).count { it.message == labels.notGrounded })
  }

  @Test
  fun `invariants are listed for the argument that will happen later`() {
    val invariants = SpecPackage.invariants("Вступление\n1. Первое\n2. Второе\nхвост")
    assertEquals(listOf("1. Первое", "2. Второе"), invariants)
  }

  @Test
  fun `paths follow one convention`() {
    assertEquals("docs/specs/login/PRODUCT.md", SpecPackage.productPath("login"))
    assertEquals("docs/specs/login/TECH.md", SpecPackage.techPath("login"))
  }

  @Test
  fun `templates are short enough to be filled`() {
    assertTrue(SpecPackage.productTemplate("login", labels).lines().size <= 8)
    assertTrue(SpecPackage.techTemplate("login", labels).lines().size <= 8)
  }
}

class MetricRunTest {
  @Test
  fun `the last number wins, because the summary comes last`() {
    val result = MetricRun.extract("compiling 3 files\nwarnings 2\nTotal time: 41.5 s", "Total time: ([0-9.]+)")
    assertEquals(41.5, result?.value)
  }

  @Test
  fun `a decimal comma is read as a decimal point`() {
    assertEquals(1.5, MetricRun.extract("время 1,5 с", "время ([0-9,]+)")?.value)
  }

  @Test
  fun `no match yields nothing rather than zero`() {
    // Ноль вместо «не нашли» превратил бы отсутствие измерения в отличный результат.
    assertNull(MetricRun.extract("ничего числового", "время ([0-9.]+)"))
  }

  @Test
  fun `a broken pattern does not crash the run`() {
    assertNull(MetricRun.extract("42", "([0-9"))
  }

  @Test
  fun `direction decides what better means`() {
    assertTrue(MetricRun.compare(before = 10.0, after = 8.0, direction = MetricRun.Direction.LOWER_IS_BETTER).improved)
    assertFalse(MetricRun.compare(before = 10.0, after = 12.0, direction = MetricRun.Direction.LOWER_IS_BETTER).improved)
    assertTrue(MetricRun.compare(before = 60.0, after = 75.0, direction = MetricRun.Direction.HIGHER_IS_BETTER).improved)
  }

  @Test
  fun `the percentage reads the way people say it`() {
    assertEquals(-20.0, MetricRun.compare(10.0, 8.0, MetricRun.Direction.LOWER_IS_BETTER).percent)
    assertEquals(0.0, MetricRun.compare(0.0, 5.0, MetricRun.Direction.LOWER_IS_BETTER).percent)
  }

  @Test
  fun `the direction is named in both languages, and defaults to lower`() {
    assertEquals(MetricRun.Direction.HIGHER_IS_BETTER, MetricRun.directionOf("больше"))
    assertEquals(MetricRun.Direction.LOWER_IS_BETTER, MetricRun.directionOf(null))
    assertEquals(MetricRun.Direction.LOWER_IS_BETTER, MetricRun.directionOf("что-то"))
  }
}
