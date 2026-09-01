// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.dap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugShapesTest {
  @Test
  fun `PHP starts by listening, JavaScript by running the file`() {
    // A PHP page is debugged through a request from the browser; running it under the CLI
    // interpreter breaks on the first page that needs a session. A JS file just runs.
    assertEquals(DebugAdapters.Shape.LISTEN, DebugAdapters.defaultShape(DebugAdapters.PHP_DEBUG))
    assertEquals(DebugAdapters.Shape.FILE, DebugAdapters.defaultShape(DebugAdapters.JS_DEBUG))
  }

  @Test
  fun `a web project can be debugged without writing JSON by hand`() {
    // The shape that was missing: everybody whose code lives behind a dev server had to hand-write
    // the configuration anyway, which is the thing this feature exists to remove.
    val json = DebugAdapters.configurationFor(
      DebugAdapters.JS_DEBUG, DebugAdapters.Shape.NPM_SCRIPT, "/p/src/app.ts", "/p")
    assertTrue("\"runtimeExecutable\": \"npm\"" in json, json)
    assertTrue("\"runtimeArgs\": [\"run\", \"dev\"]" in json, json)
    assertTrue("\"sourceMaps\": true" in json, json)
  }

  @Test
  fun `the PHP built-in server turns Xdebug on by itself`() {
    // Otherwise the configuration starts a server that will never stop anywhere, and the person
    // concludes that debugging is broken rather than that Xdebug was idle.
    val json = DebugAdapters.configurationFor(
      DebugAdapters.PHP_DEBUG, DebugAdapters.Shape.PHP_SERVER, "/p/index.php", "/p")
    assertTrue("-dxdebug.mode=debug" in json, json)
    assertTrue("-dxdebug.start_with_request=yes" in json, json)
    assertTrue("localhost:8000" in json, json)
  }

  @Test
  fun `every shape produces balanced JSON with a request`() {
    for (spec in DebugAdapters.ALL) {
      for (shape in DebugAdapters.shapes(spec)) {
        val json = DebugAdapters.configurationFor(spec, shape, "/p/f", "/p")
        assertEquals(json.count { it == '{' }, json.count { it == '}' }, json)
        assertEquals(0, json.count { it == '"' } % 2, json)
        assertTrue("\"request\":" in json, json)
      }
    }
  }

  @Test
  fun `shapes are offered per adapter, not as one list for both`() {
    // npm is not a PHP thing and the built-in PHP server is not a JavaScript one; offering either
    // in the wrong menu produces a configuration that cannot start.
    assertTrue(DebugAdapters.Shape.NPM_SCRIPT in DebugAdapters.shapes(DebugAdapters.JS_DEBUG))
    assertFalse(DebugAdapters.Shape.NPM_SCRIPT in DebugAdapters.shapes(DebugAdapters.PHP_DEBUG))
    assertTrue(DebugAdapters.Shape.PHP_SERVER in DebugAdapters.shapes(DebugAdapters.PHP_DEBUG))
    assertFalse(DebugAdapters.Shape.PHP_SERVER in DebugAdapters.shapes(DebugAdapters.JS_DEBUG))
  }
}
