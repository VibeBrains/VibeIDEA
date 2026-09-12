// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import java.util.concurrent.atomic.AtomicLong

/**
 * Идентификатор хода для журнала.
 *
 * Короткий и монотонный, а не UUID: его читают глазами в `.vibe/local/audit.jsonl`, и разбор
 * начинается с «найди все строки этого хода». Время в основе даёт естественный порядок между
 * запусками IDE, счётчик — различимость внутри одной миллисекунды.
 */
object TurnId {
  private val counter = AtomicLong(0)

  fun next(): String = "t${System.currentTimeMillis()}-${counter.incrementAndGet()}"
}
