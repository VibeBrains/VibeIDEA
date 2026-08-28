// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * «Обновить, сохранив мои тумблеры» is offered only when the user's edit really is just the
 * toggles — everything else must fall through to a human. These are the boundaries of that offer.
 */
class SeedToggleMergeTest {
  private fun file(active: Boolean, baseUrl: String = "https://api.example.com/v1", extra: String = "") = """
    {
      "version": 1,
      "providers": [
        { "id": "p1", "name": "P1", "active": $active, "baseURL": "$baseUrl", "apiKeyEnv": "K"$extra }
      ]
    }
  """.trimIndent()

  @Test
  fun flippedToggleIsDetected() {
    assertEquals(mapOf("p1" to false), SeedToggleMerge.toggleDiff(file(true), file(false)))
  }

  @Test
  fun identicalFilesYieldAnEmptyButValidMerge() {
    // Comments/formatting only — nothing to preserve, refreshing is safe.
    assertEquals(emptyMap(), SeedToggleMerge.toggleDiff(file(true), "// свой комментарий\n" + file(true)))
  }

  @Test
  fun anyOtherEditBlocksTheAutomaticMerge() {
    assertNull(SeedToggleMerge.toggleDiff(file(true), file(true, baseUrl = "https://my-proxy.local/v1")))
    assertNull(SeedToggleMerge.toggleDiff(file(true), file(true, extra = ""","timeoutMs": 1000""")))
    // A provider added or removed by the user is not a toggle either.
    assertNull(SeedToggleMerge.toggleDiff(file(true), """{"providers":[]}"""))
  }

  @Test
  fun unreadableSidesNeverMerge() {
    assertNull(SeedToggleMerge.toggleDiff(null, file(true)))
    assertNull(SeedToggleMerge.toggleDiff(file(true), null))
  }

  @Test
  fun applyRefreshesTheSeedAndRecordsTogglesInItsOwnFile() {
    val vibe = java.nio.file.Files.createTempDirectory("vibe-toggle-merge")
    // Use a real seeded path so the release content comes from the manifest.
    val relative = "providers/zai.jsonc"
    val release = checkNotNull(VibeDefaults.releaseContent(relative)) { "нет релизного содержимого" }
    java.nio.file.Files.createDirectories(vibe.resolve("providers"))
    java.nio.file.Files.writeString(vibe.resolve(relative), release.replace("\"active\": true", "\"active\": false"))

    assertEquals(true, SeedToggleMerge.apply(vibe, relative))
    // The seed is back to the release content…
    assertEquals(release, java.nio.file.Files.readString(vibe.resolve(relative)))
    // …and the decision lives in our own toggles file, last in the catalog.
    val toggles = java.nio.file.Files.readString(vibe.resolve(SeedToggleMerge.TOGGLES_FILE))
    assertEquals(true, toggles.contains("\"id\": \"zai\"") && toggles.contains("\"active\": false"))
    // Which the registry then honours: zai comes back switched off.
    val loaded = com.vibe.agent.providers.ProvidersService.loadFrom(
      java.nio.file.Files.createTempDirectory("vibe-empty-global"), vibe) { }
    assertEquals(false, loaded.any { it.id == "zai" }, "тумблер из файла должен перекрывать сид")
  }
}
