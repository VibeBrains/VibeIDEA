// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerPathsTest {
  @Test
  fun `an empty setting means the usual resolution`() {
    // Nothing has to be filled in: the page exists for the case the ordinary rule cannot express.
    assertNull(ServerPaths.overrideFor("vibeVtsls", stored = "") { true })
    assertNull(ServerPaths.overrideFor("vibeVtsls", stored = "   ") { true })
    assertNull(ServerPaths.broken("vibeVtsls", stored = "") { false })
  }

  @Test
  fun `a working path wins over every other rule`() {
    // The whole point: the projects that most need their own server keep it out of PATH.
    assertEquals("/p/vendor/bin/phpactor",
                 ServerPaths.overrideFor("vibePhpactor", stored = "/p/vendor/bin/phpactor") { true })
  }

  @Test
  fun `a path that stopped working is reported rather than ignored`() {
    // Silently falling back would leave the person debugging the server instead of the setting.
    assertNull(ServerPaths.overrideFor("vibePhpactor", stored = "/gone/phpactor") { false })
    assertEquals("/gone/phpactor", ServerPaths.broken("vibePhpactor", stored = "/gone/phpactor") { false })
    // A working one is not «broken», obviously — but the two must not both answer at once.
    assertNull(ServerPaths.broken("vibePhpactor", stored = "/ok/phpactor") { true })
  }

  @Test
  fun `overridable servers are the ones the doctor knows`() {
    // A field for a server nobody checks would be a setting with nothing behind it.
    val known = LspDoctor.ALL.map { it.id }.toSet()
    assertTrue(known.containsAll(ServerPaths.OVERRIDABLE), (ServerPaths.OVERRIDABLE - known).toString())
    assertEquals(LspDoctor.ALL.size, ServerPaths.OVERRIDABLE.size)
  }
}
