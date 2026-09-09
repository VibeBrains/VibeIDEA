// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Контракт ОБЩЕГО набора сидов: он приезжает из VibeBrains и пишется двумя продуктами сразу.
 *
 * Повод — 09.09.2026. VibeIDE переименовала поля цены в `cost*`, набор приехал к нам сабмодулем, и
 * у нас разом перестали читаться цена, срок её годности и цена «после». Ни один тест не упал:
 * разбор работал, просто спрашивал другие имена. Снаружи это выглядело как модель без цены —
 * то есть как норма.
 *
 * Поэтому здесь проверяется именно ЧУЖОЕ написание, а не наше: тест обязан падать в тот день,
 * когда общий сид снова разойдётся с нашим парсером.
 */
class SharedSeedContractTest {
  private val today = LocalDate.of(2026, 9, 9)

  private fun parse(json: String): List<ProviderEntry> =
    ProvidersFile.parse(json) { error("сид не должен быть битым: $it") }

  private fun model(json: String): ModelEntry {
    val providers = parse(json)
    assertEquals(1, providers.size)
    return providers.single().models.single()
  }

  @Test
  fun `цена читается под именем cost — так её пишет общий сид`() {
    val m = model(
      """
      { "version": 1, "providers": [ { "id": "z", "models": { "static": [
        { "id": "glm-5.3-flash", "cost": { "input": 0.075, "output": 0.25, "cacheRead": 0.015 } }
      ] } } ] }
      """
    )
    val pricing = assertNotNull(m.pricing, "цена под ключом cost обязана читаться")
    assertEquals(0.075, pricing.input)
    assertEquals(0.25, pricing.output)
    assertEquals(0.015, pricing.cacheRead)
  }

  @Test
  fun `наше прежнее имя pricing остаётся принятым синонимом`() {
    val m = model(
      """
      { "version": 1, "providers": [ { "id": "a", "models": { "static": [
        { "id": "one", "pricing": { "input": 10, "output": 50 } }
      ] } } ] }
      """
    )
    // Файлы, написанные людьми до переименования, ломаться не должны: синоним стоит одного
    // лишнего lookup, а несовместимость стоила бы им переписывания providers.json.
    assertEquals(10.0, assertNotNull(m.pricing).input)
  }

  @Test
  fun `срок годности и цена после читаются под cost-именами`() {
    val m = model(
      """
      { "version": 1, "providers": [ { "id": "z", "models": { "static": [
        { "id": "flash",
          "cost": { "input": 0.075, "output": 0.25 },
          "costValidUntil": "2026-09-09T16:00:00Z",
          "costAfter": { "input": 0.15, "output": 0.50 },
          "costNote": "стартовая акция −50%, docs.z.ai" }
      ] } } ] }
      """
    )
    assertEquals("2026-09-09T16:00:00Z", m.priceValidUntil)
    assertEquals(0.15, assertNotNull(m.priceAfter).input)
    assertEquals("стартовая акция −50%, docs.z.ai", m.priceNote)
  }

  @Test
  fun `момент с зоной — это дата, а не мусор`() {
    // Вендор объявляет срок с часом и зоной («09.09.2026 24:00 UTC+8»), и человек переписывает
    // то, что объявлено. Раньше такая строка разбиралась в null, а null здесь неотличим от
    // «срока нет»: предупреждение просто не срабатывало никогда.
    assertEquals(LocalDate.of(2026, 9, 9), ModelSunset.parse("2026-09-09T16:00:00Z"))
    assertEquals(LocalDate.of(2026, 9, 9), ModelSunset.parse("2026-09-09T23:59:59+08:00"))
    assertEquals(LocalDate.of(2026, 9, 9), ModelSunset.parse("2026-09-09T16:00:00"))
    assertEquals(LocalDate.of(2026, 9, 9), ModelSunset.parse("2026-09-09"))
    assertNull(ModelSunset.parse("девятое сентября"))
  }

  @Test
  fun `срок с моментом времени доходит до предупреждения, а источник — до отчёта`() {
    val providers = parse(
      """
      { "version": 1, "providers": [ { "id": "z", "models": { "static": [
        { "id": "flash",
          "cost": { "input": 0.075, "output": 0.25 },
          "costValidUntil": "2026-09-09T16:00:00Z",
          "costNote": "docs.z.ai/guides/overview/pricing" }
      ] } } ] }
      """
    )
    val notices = PriceValidity.notices(providers, today)
    val notice = assertNotNull(notices.singleOrNull(), "срок сегодня — это предупреждение, а не тишина")
    assertEquals(PriceValidity.State.SOON, notice.state)
    assertEquals("docs.z.ai/guides/overview/pricing", notice.note)
  }

  @Test
  fun `объявление уровней рассуждения читается и зажимает ползунок`() {
    val m = model(
      """
      { "version": 1, "providers": [ { "id": "z", "models": { "static": [
        { "id": "glm-5.3", "reasoning": { "canTurnOff": true, "effort": ["low", "high"] } }
      ] } } ] }
      """
    )
    val support = assertNotNull(m.reasoning, "объявление reasoning обязано читаться")
    assertEquals(listOf(ReasoningMode.Level.LOW, ReasoningMode.Level.HIGH), support.levels)
    // Просили medium, а модель его не принимает: берём ближайший СНИЗУ.
    assertEquals(ReasoningMode.Level.LOW, ReasoningMode.clamp(ReasoningMode.Level.MEDIUM, support))
    assertEquals(ReasoningMode.Level.HIGH, ReasoningMode.clamp(ReasoningMode.Level.HIGH, support))
    assertEquals(ReasoningMode.Level.OFF, ReasoningMode.clamp(ReasoningMode.Level.OFF, support))
  }

  @Test
  fun `нерассуждающую-по-выбору модель нельзя выключить`() {
    val support = ReasoningMode.Support(canTurnOff = false, levels = listOf(ReasoningMode.Level.MEDIUM, ReasoningMode.Level.HIGH))
    // «Выключить» у такой модели невозможно: берём самый низкий из объявленных, а не молчим.
    assertEquals(ReasoningMode.Level.MEDIUM, ReasoningMode.clamp(ReasoningMode.Level.OFF, support))
    assertEquals(ReasoningMode.Level.MEDIUM, ReasoningMode.clamp(ReasoningMode.Level.LOW, support))
  }

  @Test
  fun `модель, ничего не объявившая, ползунок не ограничивает`() {
    for (level in ReasoningMode.Level.entries) {
      assertEquals(level, ReasoningMode.clamp(level, null), "нет объявления — уровень идёт как есть")
      assertEquals(level, ReasoningMode.clamp(level, ReasoningMode.Support()))
    }
  }

  @Test
  fun `неизвестное слово уровня пропускается, а не роняет список`() {
    val m = model(
      """
      { "version": 1, "providers": [ { "id": "x", "models": { "static": [
        { "id": "new", "reasoning": { "effort": ["low", "ultra", "high"] } }
      ] } } ] }
      """
    )
    // Более новый вендор может назвать уровень так, как эта сборка ещё не знает. Терять из-за
    // одного слова весь список значит менять частичное знание на никакое.
    assertEquals(listOf(ReasoningMode.Level.LOW, ReasoningMode.Level.HIGH), assertNotNull(m.reasoning).levels)
  }

  @Test
  fun `любое время в отгружаемом наборе читается нашим разбором`() {
    // Гейт по ОТГРУЖАЕМЫМ байтам, рекомендация VibeIDE 09.09.2026 — и у нас его не было.
    //
    // Повод тот же `costValidUntil`: вендор объявляет срок моментом с зоной, `LocalDate.parse`
    // такое не берёт, а `null` здесь неотличим от «срок не указан». Предупреждение просто не
    // срабатывает — на поле, весь смысл которого в том, чтобы сработать. Ни один тест этого не
    // видел: разбор работал, он просто возвращал ничего.
    //
    // Проверка идёт по СЫРОМУ тексту, а не по разобранному дереву: закомментированный образец —
    // тоже обещание формата, человек его раскомментирует и получит молчание.
    val declared = Regex(""""(costValidUntil|priceValidUntil|sunsetDate)"\s*:\s*"([^"]*)"""")
    val unreadable = LinkedHashMap<String, MutableList<String>>()
    var seen = 0
    for (name in com.vibe.agent.defaults.VibeDefaults.manifestResourceNames()) {
      val text = javaClass.getResource("/vibeDefaults/$name")?.readText() ?: continue
      for (m in declared.findAll(text)) {
        seen++
        val value = m.groupValues[2]
        if (ModelSunset.parse(value) == null) unreadable.getOrPut(name) { ArrayList() }.add(value)
      }
    }
    assertEquals(emptyMap(), unreadable, "объявленное время, которого наш разбор не берёт")
    // Молча пустой гейт хуже отсутствующего: он рапортует успех про набор, в котором поле уже
    // переименовали. Поэтому отдельно заявляется, что проверка вообще что-то нашла.
    assertTrue(seen > 0, "в наборе не нашлось ни одного объявленного времени — поле переименовали?")
  }
}
