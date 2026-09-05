// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Линтер без конфигурации проекта поднимать незачем: правил у него нет, а выдумывать их нельзя. */
class EslintConfigTest {
  @Test
  fun `плоский конфиг ESLint 9 виден`() {
    assertTrue(EslintConfig.exists(listOf("package.json", "eslint.config.mjs")))
  }

  @Test
  fun `классические имена видны`() {
    assertTrue(EslintConfig.exists(listOf(".eslintrc.json")))
    assertTrue(EslintConfig.exists(listOf(".eslintrc")))
  }

  @Test
  fun `конфиг внутри package json тоже конфиг`() {
    assertTrue(EslintConfig.exists(listOf("package.json"), """{ "eslintConfig": { "root": true } }"""))
  }

  @Test
  fun `проект без линтера не поднимает сервер`() {
    assertFalse(EslintConfig.exists(listOf("package.json", "src"), """{ "name": "app" }"""))
    assertFalse(EslintConfig.exists(emptyList()))
  }
}
