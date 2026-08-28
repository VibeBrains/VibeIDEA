// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The decision table that keeps a release from stepping on the user's edits — and from leaving
 * an untouched old seed frozen forever. Every row of it is pinned here.
 */
class SeedRevisionsTest {
  private val rev = SeedRevision(version = 3, sha256 = "release3", history = setOf("release1", "release2"))

  @Test
  fun missingFileIsCreated() {
    assertEquals(SeedVerdict.CREATE, SeedRevisions.verdict(rev, localSha = null))
  }

  @Test
  fun identicalToReleaseIsSame() {
    assertEquals(SeedVerdict.SAME, SeedRevisions.verdict(rev, localSha = "release3"))
  }

  @Test
  fun untouchedOlderRevisionIsUpdatedSilently() {
    // The copy matches revision 2 → the user never edited it, nothing to lose.
    assertEquals(SeedVerdict.UPDATE, SeedRevisions.verdict(rev, localSha = "release2", journalVersion = 2))
    // Even without a journal entry: `history` alone proves it is an untouched old seed.
    assertEquals(SeedVerdict.UPDATE, SeedRevisions.verdict(rev, localSha = "release1"))
  }

  @Test
  fun userEditWhileReleaseMovedIsAConflict() {
    val v = SeedRevisions.verdict(rev, localSha = "mine", journalSha = "release2", journalVersion = 2)
    assertEquals(SeedVerdict.CONFLICT, v)
  }

  @Test
  fun userEditAtTheCurrentRevisionStaysQuiet() {
    assertEquals(SeedVerdict.USER_EDIT, SeedRevisions.verdict(rev, localSha = "mine", journalVersion = 3))
  }

  @Test
  fun keepMineIsNotAskedAgainUntilTheSetMovesOn() {
    // «Оставить своё» recorded at revision 3 → quiet at 3…
    assertEquals(SeedVerdict.USER_EDIT,
                 SeedRevisions.verdict(rev, localSha = "mine", journalVersion = 2, reconciledVersion = 3))
    // …and speaks up again once the set reaches revision 4.
    val moved = rev.copy(version = 4, sha256 = "release4", history = rev.history + "release3")
    assertEquals(SeedVerdict.CONFLICT,
                 SeedRevisions.verdict(moved, localSha = "mine", journalVersion = 2, reconciledVersion = 3))
  }

  @Test
  fun contentMovedWithoutABumpIsSetDrift() {
    // Journal says we already carry revision 3, the copy is our own untouched seed, yet the
    // release content differs → somebody edited the set without running bump.mjs.
    assertEquals(SeedVerdict.SET_DRIFT,
                 SeedRevisions.verdict(rev, localSha = "release2", journalSha = "release2", journalVersion = 3))
  }

  @Test
  fun crlfCheckoutOfAKnownRevisionIsNotAnEdit() {
    // Raw digest is unknown, the LF-normalized one matches revision 2 → still untouched.
    assertEquals(SeedVerdict.UPDATE, SeedRevisions.verdict(rev, localSha = "crlf-digest", localShaLf = "release2"))
    assertEquals(SeedVerdict.SAME, SeedRevisions.verdict(rev, localSha = "crlf-digest", localShaLf = "release3"))
  }

  @Test
  fun withoutARegistryTheJournalAloneDecides() {
    assertEquals(SeedVerdict.SAME, SeedRevisions.verdict(null, localSha = "x", journalSha = "x"))
    assertEquals(SeedVerdict.USER_EDIT, SeedRevisions.verdict(null, localSha = "y", journalSha = "x"))
  }

  @Test
  fun registryParsesAndDegradesGracefully() {
    val parsed = SeedRevisions.parse("""
      {"version":1,"files":{"a.md":{"version":2,"sha256":"aa","history":["a0"]},
                            "b.md":{"sha256":"bb"}}}
    """.trimIndent())
    assertEquals(SeedRevision(2, "aa", setOf("a0")), parsed["a.md"])
    assertEquals(SeedRevision(1, "bb", emptySet()), parsed["b.md"])
    assertTrue(SeedRevisions.parse(null).isEmpty())
    assertTrue(SeedRevisions.parse("{ not json").isEmpty())
  }

  @Test
  fun realRegistryOfTheSharedSetIsUsable() {
    // The embedded versions.json must cover the manifest — otherwise nothing can ever be updated.
    val registry = SeedRevisions.parse(
      SeedRevisionsTest::class.java.getResourceAsStream("/vibeDefaults/versions.json")
        ?.use { it.readBytes().toString(Charsets.UTF_8) })
    assertTrue(registry.isNotEmpty(), "versions.json набора пуст или не найден")
    val uncovered = VibeDefaults.manifestResourceNames().filter { it !in registry }
    assertEquals(emptyList(), uncovered, "файлы манифеста без записи о ревизии")
  }
}
