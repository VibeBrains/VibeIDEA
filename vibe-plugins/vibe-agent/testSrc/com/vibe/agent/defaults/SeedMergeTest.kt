// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the merge dialog's answer does to the journal, and where the merge base comes from. */
class SeedMergeTest {
  @Test
  fun dialogIsOfferedOnlyWithEnabledVcsPlugin() {
    assertTrue(SeedMerge.dialogAvailable(true))
    assertFalse(SeedMerge.dialogAvailable(false))
    assertFalse(SeedMerge.dialogAvailable(null))
  }

  @Test
  fun missingBaseIsEmptyAndSaidSo() {
    val base = SeedMerge.base(null)
    assertEquals("", base.content)
    assertFalse(base.known)
  }

  @Test
  fun storedBaseIsUsedAsIs() {
    val base = SeedMerge.base("old release\n")
    assertEquals("old release\n", base.content)
    assertTrue(base.known)
  }

  @Test
  fun resultEqualToReleaseIsTheirsEvenWithCrlf() {
    assertEquals(SeedMerge.Choice.THEIRS, SeedMerge.choice("a\nb\n", "a\nb\n"))
    assertEquals(SeedMerge.Choice.THEIRS, SeedMerge.choice("a\r\nb\r\n", "a\nb\n"))
  }

  @Test
  fun anythingElseIsMine() {
    assertEquals(SeedMerge.Choice.MINE, SeedMerge.choice("a\nmine\n", "a\nb\n"))
  }

  @Test
  fun takingTheReleaseJournalsItAsSeeded() {
    val before = VibeDefaults.JournalEntry("old", 2, reconciled = 2)
    val after = SeedMerge.journalAfter(SeedMerge.Choice.THEIRS, before, "relsha", 3)
    assertEquals(VibeDefaults.JournalEntry("relsha", 3, null), after)
  }

  @Test
  fun keepingMineRecordsReconciledAndKeepsTheSeededSha() {
    val before = VibeDefaults.JournalEntry("old", 2)
    val after = SeedMerge.journalAfter(SeedMerge.Choice.MINE, before, "relsha", 3)
    assertEquals(VibeDefaults.JournalEntry("old", 2, reconciled = 3), after)
    // The conflict does not return at the same revision.
    val rev = SeedRevision(3, "relsha", setOf("old"))
    assertEquals(SeedVerdict.USER_EDIT, SeedRevisions.verdict(
      rev, localSha = "mine", journalSha = after.sha, journalVersion = after.version, reconciledVersion = after.reconciled))
  }

  @Test
  fun takingTheReleaseMakesTheFileSame() {
    val after = SeedMerge.journalAfter(SeedMerge.Choice.THEIRS, null, "relsha", 3)
    val rev = SeedRevision(3, "relsha", emptySet())
    assertEquals(SeedVerdict.SAME, SeedRevisions.verdict(rev, localSha = "relsha", journalSha = after.sha, journalVersion = after.version))
  }

  @Test
  fun pendingDropsResolvedFiles() {
    assertEquals(listOf("b"), SeedMerge.pending(listOf("a", "b"), setOf("a")))
    assertEquals(emptyList(), SeedMerge.pending(listOf("a"), setOf("a")))
  }

  @Test
  fun seedingStoresTheSeededContentAsBase() {
    val base = Files.createTempDirectory("vibe-seed-base").toString()
    VibeDefaults.seed(base)
    val release = VibeDefaults.releaseContent("rules.md")
    assertNotNull(release)
    assertEquals(release, VibeDefaults.seedBase(base, "rules.md"))
    assertTrue(Files.isRegularFile(Path.of(base, ".vibe", "local", "seedBase", "rules.md")))
    assertNull(VibeDefaults.seedBase(base, "nope.md"))
  }

  @Test
  fun resolutionKeepsTheReleaseAsNextBase() {
    val base = Files.createTempDirectory("vibe-seed-resolve").toString()
    VibeDefaults.seed(base)
    val release = VibeDefaults.releaseContent("rules.md")!!
    Files.writeString(Path.of(base, ".vibe", "local", "seedBase", "rules.md"), "stale base")
    VibeDefaults.markReconciled(base, listOf("rules.md"))
    assertEquals(release, VibeDefaults.seedBase(base, "rules.md"))
    val entry = VibeDefaults.loadJournal(Path.of(base, ".vibe"))["rules.md"]
    assertNotNull(entry?.reconciled)
    VibeDefaults.markTaken(base, listOf("rules.md"))
    val taken = VibeDefaults.loadJournal(Path.of(base, ".vibe"))["rules.md"]
    assertEquals(VibeDefaults.sha256(release), taken?.sha)
    assertNull(taken?.reconciled)
  }
}
