// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

/**
 * Кого поднимать при открытии проекта — и можно ли вообще.
 *
 * Поле `autoStart` контракт описывал с самого начала, и всё это время оно только разбиралось:
 * человек писал «поднимай сам», IDE читала и молчала. Причина была названа честно — «нет гейта
 * доверия командам», — но с тех пор такой гейт появился (тот же, которым закрыты хуки), и
 * отговорка кончилась.
 *
 * Почему гейт вообще нужен: `servers.json` лежит В РЕПОЗИТОРИИ. Автозапуск без спроса означает,
 * что клонирование чужого проекта запускает чужие команды — на открытии, до единого нажатия.
 * Поэтому условий три, и все обязательны: запись просит автозапуск, проект доверенный, и человек
 * один раз сказал «да» ИМЕННО В ЭТОМ проекте. Ответ помнится: спрашивать каждое утро — это
 * не гейт, а привычка нажимать «да».
 *
 * Чистая: состояние приходит снаружи, здесь только решение — иначе правило нельзя проверить, не
 * открывая проект.
 */
object AutoStartPolicy {
  enum class Verdict {
    /** Автозапускать нечего: поля нет ни у одной записи. */
    NOTHING,

    /** Проекту не доверяют — команды не запускаются и вопрос не задаётся. */
    UNTRUSTED,

    /** Есть что запускать, но человека ещё не спрашивали. */
    ASK,

    /** Человек согласился в этом проекте раньше. */
    START,

    /** Человек отказался — молчим, пока он сам не передумает. */
    DECLINED,
  }

  /** Согласие хранится тремя состояниями: да, нет и «не спрашивали» — последнее не равно «нет». */
  enum class Consent { UNKNOWN, YES, NO }

  fun consentOf(stored: String?): Consent = when (stored?.trim()?.lowercase()) {
    "yes", "true" -> Consent.YES
    "no", "false" -> Consent.NO
    else -> Consent.UNKNOWN
  }

  /** Записи, просящие автозапуск. Порядок и зависимости считает [ServersFile.planStartOrder]. */
  fun wanted(entries: List<ServerEntry>): List<ServerEntry> = entries.filter { it.autoStart }

  fun decide(entries: List<ServerEntry>, trusted: Boolean, consent: Consent): Verdict = when {
    wanted(entries).isEmpty() -> Verdict.NOTHING
    // Порядок проверок важен: недоверенный проект не должен даже задавать вопрос — вопрос сам
    // по себе приглашает нажать «да» на чужих командах.
    !trusted -> Verdict.UNTRUSTED
    consent == Consent.YES -> Verdict.START
    consent == Consent.NO -> Verdict.DECLINED
    else -> Verdict.ASK
  }
}
