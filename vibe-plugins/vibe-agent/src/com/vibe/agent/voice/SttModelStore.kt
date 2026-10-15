// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * The folder the speech models live in, and what of a profile is already there
 *
 * By default the system folder of the IDE: models are large, can be downloaded again, and are not the person's settings
 * A folder named in the settings replaces it, for a machine without the internet: the models are downloaded elsewhere
 * and copied there by hand, and the IDE then never asks to download them
 * Equal by the folder, so the loaded models are recognised as the same when the store is made again for the next dictation
 */
data class SttModelStore(val root: Path) {
  /** Every file the archive must leave behind is in place */
  fun installed(archive: SttCatalog.Archive): Boolean =
    archive.files.all { Files.isRegularFile(folderOf(archive).resolve(it)) }

  /** The archives of [profile] still to download, in the order they are listed */
  fun missing(profile: SttCatalog.Profile): List<SttCatalog.Archive> = profile.archives.filterNot { installed(it) }

  fun folderOf(archive: SttCatalog.Archive): Path = root.resolve(archive.dir)

  /** The absolute path of the file that plays [role] in [model] */
  fun pathOf(model: SttCatalog.Model, role: String): String = folderOf(model.archive).resolve(model.files.getValue(role)).toString()

  companion object {
    private const val HOME_PREFIX = "~/"

    /** `<system>/vibe/stt/models` */
    fun defaultRoot(): Path = Path.of(PathManager.getSystemPath(), "vibe", "stt", "models")

    /** The store of the setting; a blank one is the default folder, and `~/` is the home folder as in a shell */
    fun of(setting: String, home: String = System.getProperty("user.home"), fallback: () -> Path = ::defaultRoot): SttModelStore {
      val typed = setting.trim()
      val root = when {
        typed.isEmpty() -> fallback()
        typed.startsWith(HOME_PREFIX) -> Path.of(home, typed.removePrefix(HOME_PREFIX))
        else -> Path.of(typed)
      }
      return SttModelStore(root)
    }
  }
}
