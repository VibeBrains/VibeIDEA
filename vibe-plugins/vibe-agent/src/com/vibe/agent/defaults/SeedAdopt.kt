// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

/**
 * Taking the release version of a seeded file — and keeping the person's copy anyway.
 *
 * The third answer the conflict notice was missing: «Сравнить» and «Оставить своё» both leave the
 * old file in place, and the person who has looked at the diff and decided «берём новое» had to
 * copy it out of the diff viewer by hand.
 *
 * Overwriting is the one destructive thing seeding ever does, so it never happens alone: the
 * previous content is saved next to the file first. Someone who edited a seeded file had a reason,
 * and «обновить» must not mean «потерять» — especially since `.vibe` is not always under git.
 */
object SeedAdopt {
  /** Where the person's version goes. Kept next to the file so it is found without a search. */
  fun backupName(relative: String): String = "$relative$BACKUP_SUFFIX"

  /**
   * The suffix is `.mine` rather than `.bak`: it says WHOSE copy it is, and the seeder never
   * touches it — an unknown extension is not part of the set.
   */
  const val BACKUP_SUFFIX = ".mine"

  /** Backups are not conflicts and must never come back as one. */
  fun isBackup(relative: String): Boolean = relative.endsWith(BACKUP_SUFFIX)
}
