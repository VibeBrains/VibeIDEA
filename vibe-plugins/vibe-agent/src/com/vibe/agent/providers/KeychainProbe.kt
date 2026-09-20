// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.util.concurrent.TimeUnit

/**
 * Есть ли ключ в связке — БЕЗ чтения его значения, то есть без диалога с паролем.
 *
 * Разница принципиальная, и она внутри самой macOS: список доступа охраняет ДАННЫЕ записи, а не её
 * существование. `SecKeychainFindGenericPassword` с пустыми полями данных (именно так платформа и
 * спрашивает перед записью) находит запись молча; чтение пароля у той же записи вызывает диалог.
 *
 * Зачем это нам. Страница «Провайдеры» показывает у каждого провайдера, сохранён ли ключ, — и
 * делала это чтением значения. Одиннадцать провайдеров при открытии страницы означали одиннадцать
 * поводов для диалога, и владелец получал их подряд, ничего не попросив (20.09.2026, 0.6.24).
 * Страница спрашивает «есть ли ключ», а не «дай ключ» — значит и спрашивать у человека нечего.
 *
 * Зовётся `security` — тот же вопрос без своего JNA-моста: связывать плагин с платформенным
 * `credential-store-impl` нельзя (он закрыт), а свой мост к Security.framework ради одного
 * булева ответа дороже, чем процесс на десять миллисекунд, который к тому же запускается только
 * при открытии страницы.
 */
object KeychainProbe {
  /** Ответ связки: есть запись, нет записи, или спросить не удалось. */
  enum class State { PRESENT, ABSENT, UNKNOWN }

  private val TIMEOUT_SECONDS = 5L

  /**
   * @param serviceName имя службы ровно в том виде, в каком его пишет платформа
   *   (`CredentialAttributes.serviceName`) — иначе спросим не про ту запись
   */
  fun probe(serviceName: String): State {
    if (!SystemInfo.isMac) return State.UNKNOWN
    return try {
      val process = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", serviceName)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return State.UNKNOWN
      }
      // 0 — запись есть; 44 (errSecItemNotFound) — записи нет; остальное означает, что спросить не
      // получилось, и это НЕ «ключа нет»: выдать «нет» на неудачный вопрос значило бы соврать.
      when (process.exitValue()) {
        0 -> State.PRESENT
        44 -> State.ABSENT
        else -> State.UNKNOWN
      }
    } catch (e: Exception) {
      logger<KeychainProbe>().warn("keychain probe for $serviceName failed: ${e.message}")
      State.UNKNOWN
    }
  }
}
