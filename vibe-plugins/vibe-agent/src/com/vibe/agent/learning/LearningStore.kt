// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.learning

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Progress on disk, one file per skill, in the project's `.vibe/learning/`.
 *
 * In the project rather than in the IDE config because a skill is usually learned FOR this project —
 * the language it is written in, the framework it uses — and a colleague picking up the branch has
 * every reason to see what was already covered.
 */
@Service(Service.Level.PROJECT)
class LearningStore(private val project: Project) {
  private val log = logger<LearningStore>()
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  fun load(skill: String): LearningPlan.Progress {
    val file = file(skill) ?: return LearningPlan.Progress(skill, null)
    if (!Files.exists(file)) return LearningPlan.Progress(skill, null)
    return runCatching { LearningPlan.decode(json.parseToJsonElement(Files.readString(file)).jsonObject) }
      .getOrElse {
        log.warn("learning progress could not be read: " + it.message)
        LearningPlan.Progress(skill, null)
      }
  }

  fun save(progress: LearningPlan.Progress) {
    val file = file(progress.skill) ?: return
    runCatching {
      Files.createDirectories(file.parent)
      Files.writeString(file, json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), LearningPlan.encode(progress)))
    }.onFailure { log.warn("learning progress could not be written: " + it.message) }
  }

  /** `RESOURCES.md` next to the progress: the sources a lesson must be taught from. */
  fun resources(): String? {
    val base = project.basePath ?: return null
    for (candidate in RESOURCE_FILES) {
      val path = Path.of(base, candidate)
      if (Files.isRegularFile(path)) return runCatching { Files.readString(path) }.getOrNull()
    }
    return null
  }

  fun list(): List<String> {
    val base = project.basePath ?: return emptyList()
    val dir = Path.of(base, DIR)
    if (!Files.isDirectory(dir)) return emptyList()
    return runCatching {
      Files.list(dir).use { it.toList() }
        .filter { it.toString().endsWith(".json") }
        .map { it.fileName.toString().removeSuffix(".json") }
        .sorted()
    }.getOrDefault(emptyList())
  }

  private fun file(skill: String): Path? {
    val base = project.basePath ?: return null
    // Letters of ANY alphabet stay in the file name: a skill called «котлин» must not become
    // «-------.json», and naming the Cyrillic range explicitly would only cover one language.
    val safe = skill.lowercase().replace(Regex("[^\\p{L}\\p{N}-]+"), "-").trim('-').ifEmpty { "skill" }
    return Path.of(base, DIR, safe + ".json")
  }

  companion object {
    const val DIR = ".vibe/learning"
    private val RESOURCE_FILES = listOf(".vibe/learning/RESOURCES.md", "docs/RESOURCES.md", "RESOURCES.md")

    fun getInstance(project: Project): LearningStore = project.getService(LearningStore::class.java)
  }
}
