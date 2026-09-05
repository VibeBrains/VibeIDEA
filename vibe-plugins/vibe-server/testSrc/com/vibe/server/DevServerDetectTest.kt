// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Догадка о дев-сервере: не выдумывать порт и не запускать чужим менеджером пакетов. */
class DevServerDetectTest {
  private val pkg = """
    {
      "name": "app",
      "scripts": { "build": "next build", "dev": "next dev", "test": "vitest" },
      "dependencies": { "next": "16.0.0" }
    }
  """.trimIndent()

  @Test
  fun `команда собирается из скрипта и менеджера по lock-файлу`() {
    assertEquals("pnpm run dev", DevServerDetect.detect(pkg, listOf("pnpm-lock.yaml"))?.command)
    assertEquals("yarn run dev", DevServerDetect.detect(pkg, listOf("yarn.lock"))?.command)
    assertEquals("npm run dev", DevServerDetect.detect(pkg, listOf("package-lock.json"))?.command)
    assertEquals("npm run dev", DevServerDetect.detect(pkg, emptyList())?.command, "без lock-файла — npm")
  }

  @Test
  fun `предпочтение скриптов - dev, потом start, потом serve`() {
    val onlyServe = """{ "scripts": { "serve": "ng serve", "lint": "eslint ." } }"""
    assertEquals("npm run serve", DevServerDetect.detect(onlyServe, emptyList())?.command)
    val startAndServe = """{ "scripts": { "serve": "ng serve", "start": "ng start" } }"""
    assertEquals("npm run start", DevServerDetect.detect(startAndServe, emptyList())?.command)
  }

  @Test
  fun `проект без подходящего скрипта не даёт записи`() {
    assertNull(DevServerDetect.detect("""{ "scripts": { "build": "tsc" } }""", emptyList()))
    assertNull(DevServerDetect.detect(null, emptyList()))
    assertNull(DevServerDetect.detect("не json вовсе", emptyList()))
  }

  @Test
  fun `порт не выдумывается - готовность по строке лога`() {
    val entry = DevServerDetect.detect(pkg, emptyList())!!
    assertNull(entry.port, "угаданный порт однажды окажется чужим сервисом")
    assertEquals("log", entry.effectiveReadyCheck)
    assertTrue(entry.readyTimeoutMs >= 420_000, "тяжёлый фронтенд поднимается минутами")
  }

  @Test
  fun `адрес берётся из того, что напечатал сам сервер`() {
    assertEquals("http://localhost:3000", DevServerDetect.urlFrom("  - Local:        http://localhost:3000"))
    assertEquals("http://127.0.0.1:5173", DevServerDetect.urlFrom("➜  Local:   http://127.0.0.1:5173/"))
    assertNull(DevServerDetect.urlFrom("compiled successfully"))
  }

  @Test
  fun `имена скриптов не путаются с зависимостями`() {
    val scripts = DevServerDetect.scriptsOf(pkg)
    assertTrue("dev" in scripts && "build" in scripts)
    assertTrue("next" !in scripts, "раздел dependencies — не скрипты")
  }
}
