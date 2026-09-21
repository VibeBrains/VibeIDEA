// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Как отвечать на вопрос «есть ли сохранённый ключ» — чистое правило, отдельно от хранилищ.
 *
 * Вынесено ради измеримости: ветка «не macOS» на машине разработчика иначе недостижима, а именно
 * она и была сломана. Прежний код сворачивал «спросить не удалось» в «ключа нет», а проба отвечает
 * «не удалось» всюду, кроме macOS, — то есть **на Windows ответ «ключа нет» выдавался всегда**,
 * при любом сохранённом ключе (скриншот владельца, 21.09.2026).
 *
 * Правило целиком: диалог с паролем вызывает только чтение значения и только у связки macOS.
 * Значит там, где связки нет, читать можно свободно, а там, где есть, — сперва проба.
 */
object KeyPresenceRule {
  /** Нужно ли читать значение, чтобы ответить. */
  fun mustReadValue(onMac: Boolean, knownInThisRun: Boolean): Boolean = !knownInThisRun && !onMac

  /**
   * Ответ по тому, что удалось узнать.
   *
   * @param probe ответ пробы связки; для не-macOS он не спрашивается вовсе
   * @param valueFound удалось ли прочитать значение (там, где чтение разрешено)
   */
  fun decide(
    onMac: Boolean,
    knownInThisRun: Boolean,
    probe: KeychainProbe.State,
    valueFound: Boolean,
  ): ApiKeyResolver.Presence = when {
    knownInThisRun -> ApiKeyResolver.Presence.PRESENT
    !onMac -> if (valueFound) ApiKeyResolver.Presence.PRESENT else ApiKeyResolver.Presence.ABSENT
    probe == KeychainProbe.State.PRESENT -> ApiKeyResolver.Presence.PRESENT
    probe == KeychainProbe.State.ABSENT -> ApiKeyResolver.Presence.ABSENT
    // Связка не ответила. Читать значение здесь нельзя — это вернёт те самые диалоги с паролем,
    // ради устранения которых проба и появилась. Поэтому говорим «не знаю», а не «нет»:
    // человек, которому сказали «ключа нет», идёт вводить ключ заново.
    else -> ApiKeyResolver.Presence.UNKNOWN
  }
}
