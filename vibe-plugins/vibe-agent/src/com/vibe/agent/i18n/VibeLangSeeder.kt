// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.i18n

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path

/**
 * Puts our own second language on disk as a FILE, and reads the folder once at startup.
 *
 * English ships the same way anyone's Elvish would: as `~/.vibe/lang/en.json`. That is the point of
 * doing it this way — if our own second language lives by the rules we ask of others, the rules are
 * tested on ourselves rather than declared.
 *
 * The seeding is create-if-missing and repeats when the file was deleted; the BASE language file is
 * never seeded, because a file always wins over the binary and a seeded copy would freeze today's
 * wording forever.
 */
class VibeLangSeeder : ProjectActivity {
  private val log = logger<VibeLangSeeder>()

  override suspend fun execute(project: Project) {
    ApplicationManager.getApplication().executeOnPooledThread {
      runCatching { seed() }.onFailure { log.warn("не удалось засеять языковые файлы: ${it.message}") }
      VibeI18n.reload()
    }
  }

  private fun seed() {
    val dir = VibeI18n.langDir()
    Files.createDirectories(dir)
    seedResource(dir.resolve("en.json"), "/lang/en.json")
    // The sample is commented and explains the format on the spot: a person editing a language
    // should not have to find the documentation first.
    seedResource(dir.resolve("README.md"), "/lang/README.md")
  }

  private fun seedResource(target: Path, resource: String) {
    if (Files.exists(target)) return
    val text = VibeLangSeeder::class.java.getResourceAsStream(resource)?.bufferedReader()?.readText() ?: return
    Files.writeString(target, text)
    log.info("засеян языковой файл: $target")
  }
}
