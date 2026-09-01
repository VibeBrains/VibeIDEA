// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.dap

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugAdaptersTest {

  @Test
  fun `the adapter is chosen by extension, and unknown files belong to nobody`() {
    assertEquals(DebugAdapters.PHP_DEBUG, DebugAdapters.adapterFor("Order.php"))
    assertEquals(DebugAdapters.JS_DEBUG, DebugAdapters.adapterFor("app.tsx"))
    // Uppercase happens on case-insensitive filesystems, and "no debugger for ORDER.PHP" would be
    // the least explicable failure in the product.
    assertEquals(DebugAdapters.PHP_DEBUG, DebugAdapters.adapterFor("ORDER.PHP"))
    assertNull(DebugAdapters.adapterFor("README.md"))
    assertNull(DebugAdapters.adapterFor("Makefile"))
  }

  @Test
  fun `the person's own install wins over ours`() {
    val spec = DebugAdapters.JS_DEBUG
    val dirs = DebugAdapters.candidateDirs(spec, home = "/home/x")
    // Both present: the LSP4IJ directory is the one they update themselves.
    val found = DebugAdapters.entryPoint(spec, dirs) { true }
    assertNotNull(found)
    assertTrue(found.toString().startsWith("/home/x/.lsp4ij/dap/vibeJsDebug"), found.toString())
    assertTrue(found.toString().endsWith("js-debug/src/dapDebugServer.js"), found.toString())
  }

  @Test
  fun `ours is used when only ours is there`() {
    val spec = DebugAdapters.JS_DEBUG
    val dirs = DebugAdapters.candidateDirs(spec, home = "/home/x")
    val found = DebugAdapters.entryPoint(spec, dirs) { it.toString().contains("/.local/share/vibe/") }
    assertNotNull(found)
    assertTrue(found.toString().contains("/.local/share/vibe/dap/vibeJsDebug"), found.toString())
  }

  @Test
  fun `a missing adapter yields no command at all`() {
    // Not a command built from a bare name: it would start nothing and fail with «Cannot run
    // program», which names the symptom and hides the cause.
    assertNull(DebugAdapters.command(DebugAdapters.JS_DEBUG, entryPoint = null))
    assertNull(DebugAdapters.command(DebugAdapters.PHP_DEBUG, entryPoint = null))
  }

  @Test
  fun `a socket adapter is given the port placeholder, a stdio one is not`() {
    val js = DebugAdapters.command(DebugAdapters.JS_DEBUG, Path.of("/a/dapDebugServer.js"), node = "/usr/bin/node")
    assertEquals("/usr/bin/node /a/dapDebugServer.js \${port} 127.0.0.1", js)

    val php = DebugAdapters.command(DebugAdapters.PHP_DEBUG, Path.of("/a/phpDebug.js"), node = "/usr/bin/node")
    assertEquals("/usr/bin/node /a/phpDebug.js", php)
    assertTrue("port" !in php!!, "an stdio adapter has no port to pass: $php")
  }

  @Test
  fun `only the socket adapter declares a ready line`() {
    // The ready line is what lets the session start exactly when the adapter is up; declaring one
    // for an stdio adapter would make us wait for a line that is never printed.
    assertNotNull(DebugAdapters.JS_DEBUG.readyPattern)
    assertNull(DebugAdapters.PHP_DEBUG.readyPattern)
  }

  @Test
  fun `the JS launch configuration debugs the file the person opened`() {
    val json = DebugAdapters.launchConfiguration(DebugAdapters.JS_DEBUG, "/p/src/app.ts", "/p")
    assertTrue("\"request\": \"launch\"" in json, json)
    assertTrue("\"program\": \"/p/src/app.ts\"" in json, json)
    assertTrue("\"type\": \"pwa-node\"" in json, json)
    // Without source maps a breakpoint in TypeScript lands nowhere, which reads as "debugging is
    // broken" rather than "the configuration is wrong".
    assertTrue("\"sourceMaps\": true" in json, json)
  }

  @Test
  fun `the PHP launch configuration listens instead of running the script`() {
    val json = DebugAdapters.launchConfiguration(DebugAdapters.PHP_DEBUG, "/p/index.php", "/p")
    // PHP is debugged through a request to the web server; a configuration that runs the file
    // under the CLI interpreter breaks on the first page that needs a session.
    assertTrue("\"program\"" !in json, json)
    assertTrue("\"port\": 9003" in json, json)
    assertTrue("\"type\": \"php\"" in json, json)
  }

  @Test
  fun `attach never launches anything`() {
    for (spec in DebugAdapters.ALL) {
      val json = DebugAdapters.attachConfiguration(spec, "/p", DebugAdapters.defaultAttachPort(spec))
      assertTrue("\"request\": \"attach\"" in json, json)
      assertTrue("\"program\"" !in json, json)
      assertTrue("\"port\": ${DebugAdapters.defaultAttachPort(spec)}" in json, json)
    }
  }

  @Test
  fun `every produced configuration is balanced JSON`() {
    // Cheap, but it is the failure this code can actually have: the JSON is assembled from
    // templates, and an unbalanced brace would only show up as a debugger that never starts.
    val all = DebugAdapters.ALL.flatMap {
      listOf(
        DebugAdapters.launchConfiguration(it, "/p/f", "/p"),
        DebugAdapters.attachConfiguration(it, "/p", DebugAdapters.defaultAttachPort(it)),
      )
    }
    for (json in all) {
      assertEquals(json.count { it == '{' }, json.count { it == '}' }, json)
      assertEquals(json.count { it == '[' }, json.count { it == ']' }, json)
      assertEquals(0, json.count { it == '"' } % 2, json)
    }
  }
}
