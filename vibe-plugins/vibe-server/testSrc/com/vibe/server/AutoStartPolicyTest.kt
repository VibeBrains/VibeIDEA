// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import kotlin.test.Test
import kotlin.test.assertEquals

/** Автозапуск чужих команд: три условия, и все обязательны. */
class AutoStartPolicyTest {
  private fun entry(id: String, auto: Boolean) = ServerEntry(id = id, command = "npm run dev", autoStart = auto)

  private val asks = listOf(entry("api", true), entry("web", false))

  @Test
  fun `без просьбы в файле не спрашиваем и не запускаем`() {
    assertEquals(
      AutoStartPolicy.Verdict.NOTHING,
      AutoStartPolicy.decide(listOf(entry("api", false)), trusted = true, consent = AutoStartPolicy.Consent.YES),
    )
  }

  @Test
  fun `недоверенный проект даже не спрашивают`() {
    // Вопрос сам по себе приглашает нажать «да» на командах из чужого репозитория.
    assertEquals(
      AutoStartPolicy.Verdict.UNTRUSTED,
      AutoStartPolicy.decide(asks, trusted = false, consent = AutoStartPolicy.Consent.UNKNOWN),
    )
    assertEquals(
      AutoStartPolicy.Verdict.UNTRUSTED,
      AutoStartPolicy.decide(asks, trusted = false, consent = AutoStartPolicy.Consent.YES),
      "прошлое согласие не переносится на проект, которому больше не доверяют",
    )
  }

  @Test
  fun `не спрашивали — это не отказ`() {
    assertEquals(
      AutoStartPolicy.Verdict.ASK,
      AutoStartPolicy.decide(asks, trusted = true, consent = AutoStartPolicy.Consent.UNKNOWN),
    )
  }

  @Test
  fun `согласие помнится, отказ тоже`() {
    assertEquals(AutoStartPolicy.Verdict.START, AutoStartPolicy.decide(asks, true, AutoStartPolicy.Consent.YES))
    assertEquals(AutoStartPolicy.Verdict.DECLINED, AutoStartPolicy.decide(asks, true, AutoStartPolicy.Consent.NO))
  }

  @Test
  fun `хранимое согласие читается терпимо, мусор значит «не спрашивали»`() {
    assertEquals(AutoStartPolicy.Consent.YES, AutoStartPolicy.consentOf("yes"))
    assertEquals(AutoStartPolicy.Consent.YES, AutoStartPolicy.consentOf("TRUE"))
    assertEquals(AutoStartPolicy.Consent.NO, AutoStartPolicy.consentOf("no"))
    assertEquals(AutoStartPolicy.Consent.UNKNOWN, AutoStartPolicy.consentOf(null))
    assertEquals(AutoStartPolicy.Consent.UNKNOWN, AutoStartPolicy.consentOf("может быть"))
  }

  @Test
  fun `запускаются только попросившие`() {
    assertEquals(listOf("api"), AutoStartPolicy.wanted(asks).map { it.id })
  }
}
