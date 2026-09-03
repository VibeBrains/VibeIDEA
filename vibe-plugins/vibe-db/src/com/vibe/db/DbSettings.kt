// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import com.intellij.ide.util.PropertiesComponent

/**
 * Пороги работы с базой — здесь, а не в коде запроса.
 *
 * Двести строк предпросмотра и тридцать секунд таймаута были вписаны константами: на большой
 * таблице человек хотел бы больше, на медленном сервере — дольше, и ни одного способа это сказать
 * у него не было. Показ системных схем тоже настройка: прятать — не то же самое, что не иметь.
 */
object DbSettings {
  private const val KEY_PREVIEW_ROWS = "vibe.db.previewRows"
  private const val KEY_QUERY_TIMEOUT = "vibe.db.queryTimeoutSeconds"
  private const val KEY_SHOW_SYSTEM_SCHEMAS = "vibe.db.showSystemSchemas"

  const val DEFAULT_PREVIEW_ROWS = 200
  const val MIN_PREVIEW_ROWS = 1
  /** Верхний предел — защита от «показать миллион»: таблица столько не нарисует, а память съест. */
  const val MAX_PREVIEW_ROWS = 100_000
  const val DEFAULT_QUERY_TIMEOUT_SECONDS = 30
  const val MIN_QUERY_TIMEOUT_SECONDS = 1
  const val MAX_QUERY_TIMEOUT_SECONDS = 3600

  private val properties: PropertiesComponent get() = PropertiesComponent.getInstance()

  var previewRows: Int
    get() = properties.getInt(KEY_PREVIEW_ROWS, DEFAULT_PREVIEW_ROWS).coerceIn(MIN_PREVIEW_ROWS, MAX_PREVIEW_ROWS)
    set(value) = properties.setValue(KEY_PREVIEW_ROWS, value.coerceIn(MIN_PREVIEW_ROWS, MAX_PREVIEW_ROWS), DEFAULT_PREVIEW_ROWS)

  var queryTimeoutSeconds: Int
    get() = properties.getInt(KEY_QUERY_TIMEOUT, DEFAULT_QUERY_TIMEOUT_SECONDS)
      .coerceIn(MIN_QUERY_TIMEOUT_SECONDS, MAX_QUERY_TIMEOUT_SECONDS)
    set(value) = properties.setValue(KEY_QUERY_TIMEOUT, value.coerceIn(MIN_QUERY_TIMEOUT_SECONDS, MAX_QUERY_TIMEOUT_SECONDS), DEFAULT_QUERY_TIMEOUT_SECONDS)

  var showSystemSchemas: Boolean
    get() = properties.getBoolean(KEY_SHOW_SYSTEM_SCHEMAS, false)
    set(value) = properties.setValue(KEY_SHOW_SYSTEM_SCHEMAS, value, false)
}
