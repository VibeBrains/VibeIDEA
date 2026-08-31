// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThrashDetectorTest {
  private fun ok(n: Int = 1) = List(n) { ThrashDetector.Event("вызов$it", ThrashDetector.Outcome.OK) }
  private fun err(n: Int) = List(n) { ThrashDetector.Event("вызов$it", ThrashDetector.Outcome.ERROR) }
  private fun timeout(name: String, n: Int) = List(n) { ThrashDetector.Event(name, ThrashDetector.Outcome.TIMEOUT) }

  @Test
  fun `нормальный ход не тревожит никого`() {
    assertEquals(ThrashDetector.Verdict.OK, ThrashDetector.check(ok(10)).verdict)
    assertEquals(ThrashDetector.Verdict.OK, ThrashDetector.check(emptyList()).verdict)
  }

  @Test
  fun `одна и та же команда, зависшая трижды, — это не невезение`() {
    val history = ok(3) + timeout("npm run build", 3)
    val finding = ThrashDetector.check(history)
    assertEquals(ThrashDetector.Verdict.REPEATED_TIMEOUT, finding.verdict)
    assertEquals("npm run build", finding.detail)
    assertEquals(3, finding.count)
  }

  @Test
  fun `таймауты РАЗНЫХ команд предохранитель не считает одной бедой`() {
    val history = timeout("сборка", 2) + timeout("тесты", 2) + ok(2)
    assertEquals(ThrashDetector.Verdict.OK, ThrashDetector.check(history).verdict)
  }

  @Test
  fun `успех между таймаутами счётчик НЕ обнуляет`() {
    // Ровно та дыра, из-за которой чужой агент перезапускал зависшую команду три сессии подряд.
    val history = listOf(
      ThrashDetector.Event("сборка", ThrashDetector.Outcome.TIMEOUT),
      ThrashDetector.Event("чтение", ThrashDetector.Outcome.OK),
      ThrashDetector.Event("сборка", ThrashDetector.Outcome.TIMEOUT),
      ThrashDetector.Event("чтение", ThrashDetector.Outcome.OK),
      ThrashDetector.Event("сборка", ThrashDetector.Outcome.TIMEOUT),
    )
    assertEquals(ThrashDetector.Verdict.REPEATED_TIMEOUT, ThrashDetector.check(history).verdict)
  }

  @Test
  fun `половина окна в отказах — это уже не полоса невезения`() {
    val finding = ThrashDetector.check(err(6) + ok(4))
    assertEquals(ThrashDetector.Verdict.THRASH, finding.verdict)
    assertEquals(6, finding.count)
    assertEquals(ThrashDetector.Verdict.THRASH, ThrashDetector.check(err(5) + ok(5)).verdict)
    assertEquals(ThrashDetector.Verdict.OK, ThrashDetector.check(err(4) + ok(6)).verdict)
  }

  @Test
  fun `ошибки, перемежающиеся успехами, тоже ловятся`() {
    // Счётчик «подряд» здесь молчит, а бюджет тратится.
    val mixed = (1..5).flatMap {
      listOf(ThrashDetector.Event("a$it", ThrashDetector.Outcome.ERROR),
             ThrashDetector.Event("b$it", ThrashDetector.Outcome.OK))
    }
    // Счётчик «подряд» тут не срабатывает никогда: каждый второй вызов успешен.
    assertEquals(ThrashDetector.Verdict.THRASH, ThrashDetector.check(mixed).verdict)
  }

  @Test
  fun `неполное окно приговором не считается`() {
    // Три провала из трёх вызовов — тяжёлое начало, а не закономерность.
    assertEquals(ThrashDetector.Verdict.OK, ThrashDetector.check(err(3)).verdict)
  }

  @Test
  fun `история подрезается, а вердикт не меняется`() {
    val long = ok(50) + err(6) + ok(4)
    assertEquals(ThrashDetector.WINDOW, ThrashDetector.trim(long).size)
    assertEquals(ThrashDetector.check(long).verdict, ThrashDetector.check(ThrashDetector.trim(long)).verdict)
  }

  @Test
  fun `таймаут узнаётся по словам агента на обоих языках`() {
    // ACP отдаёт один статус отказа и ничего мельче, поэтому читаем формулировку.
    assertTrue(ThrashDetector.looksLikeTimeout("Command timed out after 120s"))
    assertTrue(ThrashDetector.looksLikeTimeout("Таймаут выполнения"))
    assertTrue(ThrashDetector.looksLikeTimeout("не дождались ответа"))
    assertFalse(ThrashDetector.looksLikeTimeout("File not found"))
    assertFalse(ThrashDetector.looksLikeTimeout(null))
  }
}
