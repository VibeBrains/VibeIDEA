// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Гейт на три значения, из-за которых `textDocument/codeAction` падал на Windows-сборке 0.5.0.
 *
 * Тест сторожит именно их: вернувшаяся пустая строка в `nodePath`, пропавший `workspaceFolder` или
 * снова выключенная плоская конфигурация — это возврат того же дефекта, а снаружи он выглядит
 * ошибкой сервера, а не нашей.
 */
class EslintSettingsTest {
  private fun eslint(uri: String?) = eslintSettings(uri).getAsJsonObject("eslint")

  @Test
  fun `nodePath is null so the server resolves node itself`() {
    val nodePath = eslint("file:///work/proj").get("nodePath")
    assertTrue(nodePath.isJsonNull, "nodePath обязан быть null, пустая строка ломает склейку пути")
  }

  @Test
  fun `workspace folder is sent when the project has a base path`() {
    val folder = eslint("file:///work/proj").getAsJsonObject("workspaceFolder")
    assertEquals("file:///work/proj", folder.get("uri").asString)
    assertEquals("proj", folder.get("name").asString)
  }

  @Test
  fun `workspace folder is omitted when there is no base path`() {
    assertNull(eslint(null).get("workspaceFolder"))
  }

  @Test
  fun `flat config mode is left to the server`() {
    val experimental = eslint(null).getAsJsonObject("experimental")
    assertNotNull(experimental, "объект обязателен: сервер читает его без проверки на undefined")
    assertNull(experimental.get("useFlatConfig"), "ESLint 9 работает только на плоской конфигурации")
  }

  @Test
  fun `saving does not rewrite the file`() {
    val onSave = eslint(null).getAsJsonObject("codeActionOnSave")
    assertFalse(onSave.get("enable").asBoolean)
  }

  @Test
  fun `base path becomes a file uri without a trailing slash`() {
    val uri = workspaceFolderUri("/work/proj")
    assertEquals("file:///work/proj", uri)
  }

  @Test
  fun `blank base path yields no uri`() {
    assertNull(workspaceFolderUri(null))
    assertNull(workspaceFolderUri("  "))
  }
}
