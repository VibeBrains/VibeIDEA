// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsProjectConfigTest {
  private fun project(
    hasConfig: Boolean = false,
    files: Int = 500,
    sources: List<String> = listOf("jscore"),
    vendors: List<String> = listOf("extjs6"),
  ) = JsProjectConfig.Project(hasConfig, files, sources, vendors)

  @Test
  fun `предлагаем только там, где выведенного проекта не хватает`() {
    assertTrue(JsProjectConfig.needsConfig(project()))
    // Уже настроено — не лезем.
    assertFalse(JsProjectConfig.needsConfig(project(hasConfig = true)))
    // Три скрипта: выведенного проекта хватает, а предложение на пустом месте учит отмахиваться.
    assertFalse(JsProjectConfig.needsConfig(project(files = 3)))
    assertFalse(JsProjectConfig.needsConfig(project(sources = emptyList())))
  }

  @Test
  fun `вендорные каталоги узнаются по имени и по префиксу версии`() {
    assertTrue(JsProjectConfig.isVendor("node_modules"))
    assertTrue(JsProjectConfig.isVendor("extjs6"), "extjs6, extjs4 — та же библиотека с версией в имени")
    assertTrue(JsProjectConfig.isVendor("jquery-3.6.0"))
    assertTrue(JsProjectConfig.isVendor("/repo/promed/.vscode"))
    assertFalse(JsProjectConfig.isVendor("jscore"), "свой код вендорным не считается")
  }

  @Test
  fun `файл содержит свои каталоги и исключает чужие`() {
    val text = JsProjectConfig.content(project(sources = listOf("jscore", "components"), vendors = listOf("extjs6", "extjs4")))
    assertTrue(text.contains("\"components/**/*.js\""), text)
    assertTrue(text.contains("\"jscore/**/*.js\""), text)
    assertTrue(text.contains("\"extjs6\""))
    assertTrue(text.contains("\"node_modules\""), "постоянные исключения добавляются к найденным")
  }

  @Test
  fun `checkJs выключен намеренно`() {
    // Включённый он засыпает старую кодовую базу тысячами ошибок в первый же день, и человек
    // выключает уже весь сервер. Файл нужен, чтобы сервер УВИДЕЛ файлы, а не начал их судить.
    assertTrue(JsProjectConfig.content(project()).contains("\"checkJs\": false"))
    assertTrue(JsProjectConfig.content(project()).contains("\"allowJs\": true"))
  }

  @Test
  fun `текст файла закреплён целиком`() {
    // Эталон, а не проверки подстрок: файл читает чужой сервер, и лишняя запятая или съехавший
    // отступ означают, что он молча не прочитается, а человек будет искать причину в другом месте.
    assertEquals(
      """
      {
        "compilerOptions": {
          "allowJs": true,
          "checkJs": false,
          "target": "es2017",
          "moduleResolution": "node"
        },
        "include": [
          "src/**/*.js"
        ],
        "exclude": [
          ".git",
          ".idea",
          ".vibe",
          ".vscode",
          "bower_components",
          "build",
          "coverage",
          "dist",
          "docs",
          "extjs6",
          "node_modules",
          "out",
          "target",
          "temp",
          "tmp",
          "vendor"
        ]
      }
      """.trimIndent() + "\n",
      JsProjectConfig.content(project(sources = listOf("src"), vendors = listOf("extjs6"))),
    )
  }

  @Test
  fun `получившийся файл — валидный JSON`() {
    val text = JsProjectConfig.content(project(sources = listOf("a", "b"), vendors = listOf("v")))
    // Скобки сбалансированы, запятых в конце списков нет — иначе tsserver молча не прочитает файл.
    assertEquals(text.count { it == '{' }, text.count { it == '}' })
    assertEquals(text.count { it == '[' }, text.count { it == ']' })
    assertFalse(text.contains(",\n  ]"), "висячая запятая в списке")
    assertFalse(text.contains(",\n}"), "висячая запятая в объекте")
  }
}
