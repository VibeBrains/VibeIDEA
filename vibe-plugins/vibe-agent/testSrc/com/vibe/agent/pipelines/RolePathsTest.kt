// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Права шага на пути. Половина проверок — про то, что ограничения НЕТ там, где его не объявляли:
 * ограничение, взявшееся из ниоткуда, ломает обычный чат, и заметно это не сразу.
 */
class RolePathsTest {
  private val none = RolePaths.Scope()

  @Test
  fun `без объявления пишется куда угодно`() {
    assertTrue(RolePaths.mayWrite("src/main.kt", none))
    assertTrue(RolePaths.mayWrite("любой/путь.txt", none))
  }

  @Test
  fun `разрешения работают как белый список`() {
    val scope = RolePaths.Scope(allow = listOf("tests/**"))
    assertTrue(RolePaths.mayWrite("tests/a/b/FooTest.kt", scope))
    assertFalse(RolePaths.mayWrite("src/Foo.kt", scope))
  }

  @Test
  fun `двойная звёздочка покрывает и сам каталог`() {
    val scope = RolePaths.Scope(allow = listOf("docs/**"))
    assertTrue(RolePaths.mayWrite("docs/readme.md", scope))
    assertTrue(RolePaths.mayWrite("docs/a/b/c.md", scope))
    assertFalse(RolePaths.mayWrite("docsx/readme.md", scope), "docs не должен совпадать с docsx")
  }

  @Test
  fun `одна звёздочка не переходит через разделитель`() {
    val scope = RolePaths.Scope(allow = listOf("src/*.kt"))
    assertTrue(RolePaths.mayWrite("src/Foo.kt", scope))
    assertFalse(RolePaths.mayWrite("src/deep/Foo.kt", scope))
  }

  @Test
  fun `шаблон без разделителя ловит имя на любой глубине`() {
    // Правило .gitignore, и люди пишут `*.md`, имея в виду именно его.
    val scope = RolePaths.Scope(allow = listOf("*.md"))
    assertTrue(RolePaths.mayWrite("README.md", scope))
    assertTrue(RolePaths.mayWrite("docs/vibe/roadmap.md", scope))
    assertFalse(RolePaths.mayWrite("docs/vibe/roadmap.txt", scope))
  }

  @Test
  fun `запрет сильнее разрешения`() {
    val scope = RolePaths.Scope(allow = listOf("**"), deny = listOf("**/secrets/**"))
    assertTrue(RolePaths.mayWrite("src/Foo.kt", scope))
    assertFalse(RolePaths.mayWrite("src/secrets/key.pem", scope))
    // Порядок правил роли не играет: решает вид, а не позиция — как у Claude Code.
    val reversed = RolePaths.Scope(allow = listOf("src/secrets/**", "**"), deny = listOf("**/secrets/**"))
    assertFalse(RolePaths.mayWrite("src/secrets/key.pem", reversed))
  }

  @Test
  fun `один запрет без разрешений оставляет остальное открытым`() {
    val scope = RolePaths.Scope(deny = listOf("build/**"))
    assertFalse(RolePaths.mayWrite("build/out.jar", scope))
    assertTrue(RolePaths.mayWrite("src/Foo.kt", scope))
  }

  @Test
  fun `каталог со слэшем на конце значит всё его содержимое`() {
    val scope = RolePaths.Scope(allow = listOf("tests/"))
    assertTrue(RolePaths.mayWrite("tests/FooTest.kt", scope))
    assertFalse(RolePaths.mayWrite("src/Foo.kt", scope))
  }

  @Test
  fun `разделители Windows приводятся к общему виду`() {
    val scope = RolePaths.Scope(allow = listOf("tests/**"))
    assertTrue(RolePaths.mayWrite("tests\\unit\\FooTest.kt", scope))
  }

  @Test
  fun `маршрут с двумя точками — отказ, а не совпадение`() {
    // `tests/../src/app.kt` совпадал с `tests/**` как текст и ложился в src.
    val scope = RolePaths.Scope(allow = listOf("tests/**"))
    assertFalse(RolePaths.mayWrite("tests/../src/app.kt", scope))
    assertFalse(RolePaths.mayWrite("../outside.kt", scope))
    assertTrue(RolePaths.mayWrite("tests/unit/FooTest.kt", scope), "обычный путь не задет")
  }

  @Test
  fun `запрет не обходится регистром и формой юникода`() {
    val scope = RolePaths.Scope(allow = listOf("**"), deny = listOf("**/secrets/**"))
    assertFalse(RolePaths.mayWrite("Secrets/key.txt", scope))
    assertFalse(RolePaths.mayWrite("src/SECRETS/key.txt", scope))
    assertTrue(RolePaths.mayWrite("src/Main.kt", scope))
  }

  @Test
  fun `и то и другое сразу — порядок проверок не даёт лазейки`() {
    // Урок VibeSweep: по отдельности обе нормализации верны, вместе — нет, если порядок неверен.
    val scope = RolePaths.Scope(allow = listOf("tests/**"), deny = listOf("**/secrets/**"))
    assertFalse(RolePaths.mayWrite("tests/../Secrets/key.txt", scope))
  }

  @Test
  fun `разрешение сравнивается точно`() {
    // На диске, чувствительном к регистру, Tests/ — другая папка; точный промах ошибается в сторону отказа.
    assertFalse(RolePaths.mayWrite("Tests/FooTest.kt", RolePaths.Scope(allow = listOf("tests/**"))))
  }
}
