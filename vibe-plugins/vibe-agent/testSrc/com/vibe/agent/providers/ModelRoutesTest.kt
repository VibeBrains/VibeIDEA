// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.pipelines.StepModelRef
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Логические имена моделей: `@fast` вместо адреса, переписанного руками в семи местах.
 *
 * Две семантики взяты у AIP-57: слои (проект перекрывает глобальное) и `null` как ЗАПРЕТ имени,
 * а не как его отсутствие. Второе и есть причина тестировать: без него «выключил дорогую модель
 * в проекте» означало бы «проект берёт её из глобального файла».
 */
class ModelRoutesTest {
  private val routes = mapOf("fast" to "minimax/MiniMax-M3", "smart" to "anthropic/claude-opus-5")

  @Test
  fun `a reference is recognised by its sign, and an address is not one`() {
    assertTrue(ModelRoutes.isReference("@fast"))
    assertTrue(ModelRoutes.isReference("  @smart "))
    assertFalse(ModelRoutes.isReference("minimax/MiniMax-M3"))
    assertFalse(ModelRoutes.isReference("@"))
    assertFalse(ModelRoutes.isReference(null))
  }

  @Test
  fun `a name resolves to the address it points at`() {
    assertEquals(ModelRoutes.Resolution.Found("minimax", "MiniMax-M3"), ModelRoutes.resolve("@fast", routes))
  }

  @Test
  fun `the project layer wins, and null closes the name rather than removing it`() {
    val merged = ModelRoutes.merge(listOf(routes, mapOf("fast" to "zai/glm-5.3", "smart" to null)))
    assertEquals(ModelRoutes.Resolution.Found("zai", "glm-5.3"), ModelRoutes.resolve("@fast", merged))
    // Именно запрет: нижний слой знает адрес, но проект закрыл имя, и молча взять адрес нельзя.
    assertEquals(ModelRoutes.Resolution.Disabled, ModelRoutes.resolve("@smart", merged))
  }

  @Test
  fun `an unknown name names what is declared, and a broken value names itself`() {
    assertEquals(ModelRoutes.Resolution.Unknown(listOf("fast", "smart")), ModelRoutes.resolve("@cheap", routes))
    assertEquals(ModelRoutes.Resolution.Malformed("glm-5.3"),
                 ModelRoutes.resolve("@x", mapOf("x" to "glm-5.3")))
  }

  @Test
  fun `a step takes the address behind the name`() {
    assertEquals("minimax" to "MiniMax-M3", StepModelRef.resolve(null, "@fast", routes))
    // Прежние написания не тронуты: адрес одной строкой и пара полей.
    assertEquals("zai" to "glm-5.3", StepModelRef.resolve(null, "zai/glm-5.3", routes))
    assertEquals("zai" to "glm-5.3", StepModelRef.resolve("zai", "glm-5.3", routes))
    assertEquals(null to null, StepModelRef.resolve(null, null, routes))
  }

  @Test
  fun `a step with an unusable name fails loudly instead of running on the role's model`() {
    // Тихая подмена здесь стоит денег: шаг выглядит работающим и идёт к другой модели.
    assertFailsWith<IllegalArgumentException> { StepModelRef.resolve(null, "@cheap", routes) }
    assertFailsWith<IllegalArgumentException> { StepModelRef.resolve(null, "@off", mapOf("off" to null)) }
  }

  @Test
  fun `the project catalog outranks the global providers json`(@TempDir dir: Path) {
    // The shared vectors give these two layers different names, so they cannot tell the order apart; the contract can
    val global = dir.resolve("home/.vibe")
    val project = dir.resolve("project/.vibe")
    write(global.resolve("providers.json"), """{ "routes": { "fast": "zai/glm-5.3" }, "providers": [] }""")
    write(project.resolve("providers/routes.jsonc"), """{ "routes": { "fast": "minimax/MiniMax-M3" }, "providers": [] }""")
    assertEquals(ModelRoutes.Resolution.Found("minimax", "MiniMax-M3"),
                 ModelRoutes.resolve("@fast", ProvidersService.loadRoutes(global, project) { }))
  }

  private fun write(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
  }

  @Test
  fun `routes are read from the providers file, with null preserved`() {
    val text = """
      { "version": 1,
        "routes": { "fast": "minimax/MiniMax-M3", "vision": null, "wrong": 7 },
        "providers": [] }
    """.trimIndent()
    val warnings = ArrayList<String>()
    val parsed = ProvidersFile.parseRoutes(text, "providers.json") { warnings.add(it) }
    assertEquals(mapOf("fast" to "minimax/MiniMax-M3", "vision" to null), parsed)
    assertEquals(1, warnings.size, warnings.toString())
  }
}
