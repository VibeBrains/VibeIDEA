// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningModeTest {
  @Test
  fun `the level is read in both languages and defaults to off`() {
    assertEquals(ReasoningMode.Level.LOW, ReasoningMode.levelOf("низкий"))
    assertEquals(ReasoningMode.Level.HIGH, ReasoningMode.levelOf("HIGH"))
    assertEquals(ReasoningMode.Level.OFF, ReasoningMode.levelOf(null))
    assertEquals(ReasoningMode.Level.OFF, ReasoningMode.levelOf("что-то"))
  }

  @Test
  fun `off adds nothing to any provider`() {
    for (protocol in listOf("anthropic", "gemini", "openai")) {
      assertTrue(ReasoningMode.bodyFields(protocol, ReasoningMode.Level.OFF, 8000).isEmpty())
    }
  }

  @Test
  fun `each provider is spoken to in its own dialect`() {
    // reasoning_effort, отправленный Anthropic, не делает НИЧЕГО и молча: человек решает,
    // что ползунок декоративный.
    val anthropic = ReasoningMode.bodyFields("anthropic", ReasoningMode.Level.MEDIUM, 32_000)
    assertEquals("enabled", anthropic["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    val gemini = ReasoningMode.bodyFields("gemini", ReasoningMode.Level.MEDIUM, 32_000)
    assertTrue(gemini.containsKey("generationConfig"))
    val openai = ReasoningMode.bodyFields("openai", ReasoningMode.Level.MEDIUM, 32_000)
    assertEquals("medium", openai["reasoning_effort"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the thinking budget leaves room for the answer`() {
    // Бюджет, равный max_tokens, API отклоняет, и выглядит это как «модель молчит».
    val body = ReasoningMode.bodyFields("anthropic", ReasoningMode.Level.HIGH, 8_000)
    val budget = body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt()
    assertTrue(budget <= 8_000 - ReasoningMode.MIN_ANSWER_TOKENS, "бюджет $budget не оставляет места ответу")
  }

  @Test
  fun `an unknown max_tokens still yields a usable budget`() {
    val body = ReasoningMode.bodyFields("anthropic", ReasoningMode.Level.HIGH, null)
    val budget = body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt()
    assertTrue(budget >= 1_024)
  }

  @Test
  fun `deeper levels ask for more`() {
    assertTrue(ReasoningMode.budgetTokens(ReasoningMode.Level.LOW)!! <
                 ReasoningMode.budgetTokens(ReasoningMode.Level.MEDIUM)!!)
    assertTrue(ReasoningMode.budgetTokens(ReasoningMode.Level.MEDIUM)!! <
                 ReasoningMode.budgetTokens(ReasoningMode.Level.HIGH)!!)
  }

  @Test
  fun `верхнее положение отправляет верхнее слово ВЕНДОРА, а не наше`() {
    // GLM-5.3 объявляет low/high/max и рекомендует max для кода. Наше перечисление схлопывало
    // max в high, и на верхнем положении ползунка модель получала «поменьше» — молча.
    val glm = ReasoningMode.Support(canTurnOff = false, words = listOf("low", "high", "max"))
    assertEquals("max", ReasoningMode.effortWord(ReasoningMode.Level.HIGH, glm))
    assertEquals("low", ReasoningMode.effortWord(ReasoningMode.Level.LOW, glm))
    assertEquals("high", ReasoningMode.effortWord(ReasoningMode.Level.MEDIUM, glm), "середина списка вендора")
  }

  @Test
  fun `словарь длиннее нашего тоже читается по краям`() {
    // GPT-6 Astra: low/medium/high/xhigh/max — пять уровней против наших трёх.
    val astra = ReasoningMode.Support(words = listOf("low", "medium", "high", "xhigh", "max"))
    assertEquals("max", ReasoningMode.effortWord(ReasoningMode.Level.HIGH, astra))
    assertEquals("low", ReasoningMode.effortWord(ReasoningMode.Level.LOW, astra))
    assertEquals("high", ReasoningMode.effortWord(ReasoningMode.Level.MEDIUM, astra))
  }

  @Test
  fun `без объявления отправляем своё слово`() {
    // Придумывать за вендора список мы не вправе, а молчать значило бы отключить ползунок тем,
    // кто ничего не объявлял.
    assertEquals("high", ReasoningMode.effortWord(ReasoningMode.Level.HIGH, null))
    assertEquals("high", ReasoningMode.effortWord(ReasoningMode.Level.HIGH, ReasoningMode.Support(canTurnOff = false)))
    assertNull(ReasoningMode.effortWord(ReasoningMode.Level.OFF, ReasoningMode.Support(words = listOf("low", "max"))))
  }

  @Test
  fun `тело запроса несёт слово вендора`() {
    val glm = ReasoningMode.Support(canTurnOff = false, words = listOf("low", "high", "max"))
    val body = ReasoningMode.bodyFields("openai", ReasoningMode.Level.HIGH, 128_000, glm)
    assertEquals("max", body["reasoning_effort"]?.jsonPrimitive?.content)
  }

  // DeepSeek V4.1 thinks at `high` unless the request says otherwise (api-docs.deepseek.com, 11.09.2026).
  private val disabled = buildJsonObject { put("thinking", buildJsonObject { put("type", "disabled") }) }
  private val deepseek = ReasoningMode.Support(canTurnOff = true, words = listOf("low", "high", "max"), off = disabled)

  @Test
  fun `выключение отправляет объявленный фрагмент`() {
    // Без фрагмента «выкл» не отправлял ничего, и модель думала дальше — ползунок врал.
    val body = ReasoningMode.bodyFields("openai", ReasoningMode.Level.OFF, 8_000, deepseek)
    assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    assertNull(body["reasoning_effort"], "при выключении уровень не отправляется")
  }

  @Test
  fun `включённое рассуждение фрагмента выключения не несёт`() {
    val body = ReasoningMode.bodyFields("openai", ReasoningMode.Level.HIGH, 8_000, deepseek)
    assertNull(body["thinking"])
    assertEquals("max", body["reasoning_effort"]!!.jsonPrimitive.content)
  }

  @Test
  fun `модель, которая не выключается, фрагмента не получает`() {
    // Противоречивое объявление (canTurnOff=false и off) решается в пользу canTurnOff: clamp переводит
    // «выкл» в самый низкий уровень, и до фрагмента дело не доходит.
    val odd = ReasoningMode.Support(canTurnOff = false, words = listOf("low", "high"), off = disabled)
    val level = ReasoningMode.clamp(ReasoningMode.Level.OFF, odd)
    assertEquals(ReasoningMode.Level.LOW, level)
    assertNull(ReasoningMode.bodyFields("openai", level, 8_000, odd)["thinking"])
  }

  @Test
  fun `фрагмент выключения читается из файла как есть`() {
    val parsed = ProvidersFile.parse(
      """{"providers":[{"id":"d","baseURL":"https://x","models":{"static":[
         {"id":"m","reasoning":{"canTurnOff":true,"off":{"thinking":{"type":"disabled"}}}}]}}]}""", "test") { }
    val support = parsed.single().models.single().reasoning!!
    assertEquals("disabled", support.off!!["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
  }
}
