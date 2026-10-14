// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project's answer to "what must you read, and what must you not touch": rules, reference
 * folders and the ignore list, read from disk and cached until the files change.
 *
 * Caching is by modification time rather than by a timer: a rule edited in the editor must reach
 * the very next turn, and rereading four files on every keystroke would be the other extreme.
 */
@Service(Service.Level.PROJECT)
class ProjectContextService(private val project: Project) {
  private val log = logger<ProjectContextService>()

  private data class Cached<T>(val value: T, val stamp: Long)

  @Volatile private var rules: Cached<List<ProjectRules.Rule>>? = null
  @Volatile private var ignore: Cached<VibeIgnore>? = null

  /** Absolute folders outside the project the agent may read; empty by default. */
  fun referenceFolders(): List<String> =
    PropertiesComponent.getInstance(project).getValue(KEY_REFERENCE_FOLDERS).orEmpty()
      .split(PATH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

  fun setReferenceFolders(folders: List<String>) {
    PropertiesComponent.getInstance(project).setValue(KEY_REFERENCE_FOLDERS, folders.joinToString(PATH_SEPARATOR))
  }

  /** Paths inside the project the agent may read but never write. */
  fun sourceFolders(): List<String> =
    PropertiesComponent.getInstance(project).getValue(KEY_SOURCE_FOLDERS).orEmpty()
      .split(PATH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

  fun setSourceFolders(folders: List<String>) {
    PropertiesComponent.getInstance(project).setValue(KEY_SOURCE_FOLDERS, folders.joinToString(PATH_SEPARATOR))
  }

  fun roots(): AccessPolicy.Roots = AccessPolicy.Roots(
    // Physical spellings, resolved the same way as agent paths: a project opened through a symlink
    // (or through macOS `/var` → `/private/var`) would otherwise be a stranger to itself.
    projectBase = project.basePath?.let { physical(it) },
    referenceFolders = referenceFolders().map { physical(it) },
    sourceFolders = sourceFolders(),
    ignore = ignore(),
  )

  private fun physical(path: String): String =
    runCatching { AgentPaths.physical(Path.of(path).toAbsolutePath().normalize())?.toString() }.getOrNull() ?: path

  fun ignore(): VibeIgnore {
    val base = project.basePath ?: return VibeIgnore.EMPTY
    val file = Path.of(base, VibeIgnore.FILE)
    val stamp = stampOf(file)
    ignore?.let { if (it.stamp == stamp) return it.value }
    val parsed = if (stamp == 0L) VibeIgnore.EMPTY
                 else runCatching { VibeIgnore.parse(Files.readString(file)) }.getOrElse {
                   log.warn("$file could not be read: ${it.message}")
                   VibeIgnore.EMPTY
                 }
    ignore = Cached(parsed, stamp)
    return parsed
  }

  /**
   * Rules for this turn: the root ones plus those of the folders the touched files live in.
   *
   * Nested rules are read on demand rather than cached: the set of folders changes with every turn,
   * and a cache keyed by it would be a cache that never hits. There are at most a handful of them,
   * each a few kilobytes.
   */
  fun rules(touchedPaths: List<String>): List<ProjectRules.Rule> {
    val base = project.basePath ?: return emptyList()
    val root = rules()
    val nested = ArrayList<ProjectRules.Rule>()
    for (dir in ProjectRules.ruleDirsFor(touchedPaths)) {
      if (dir.isEmpty()) continue
      // Вложенный AGENTS.md — та же конвенция близости: у пакета своё соглашение, и оно бьёт
      // корневое для файлов внутри пакета.
      agentsRule(Path.of(base, dir, ProjectRules.AGENTS_FILE), dir)?.let { nested.add(it) }
      nested.addAll(ProjectRuleFiles.read(Path.of(base), dir))
    }
    return if (nested.isEmpty()) root else ProjectRules.nearestWins(root + nested)
  }

  /**
   * Every rule the project declares, in a stable order: `.cursor/rules` and `.vibe/rules` (see [ProjectRuleFiles]),
   * then the legacy `.cursorrules` as a rule that always applies — that is what the file meant before
   * the folder format existed — and `AGENTS.md` last
   */
  fun rules(): List<ProjectRules.Rule> {
    val base = project.basePath ?: return emptyList()
    val legacy = Path.of(base, ProjectRules.LEGACY_FILE)
    val agents = Path.of(base, ProjectRules.AGENTS_FILE)
    // Отпечаток считает и AGENTS.md: без этого правка файла не долетела бы до следующего хода —
    // кэш правил остался бы прежним, и человек решил бы, что агент его не слушает.
    val stamp = ProjectRuleFiles.stamp(Path.of(base)) + stampOf(legacy) + stampOf(agents) * 17
    rules?.let { if (it.stamp == stamp) return it.value }

    val loaded = ArrayList(ProjectRuleFiles.read(Path.of(base)))
    if (Files.isRegularFile(legacy)) {
      runCatching { Files.readString(legacy) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
        loaded.add(ProjectRules.Rule(ProjectRules.LEGACY_FILE, dir = "", description = null, globs = emptyList(),
                                     alwaysApply = true, body = it.trim(), source = ProjectRules.LEGACY_FILE))
      }
    }
    agentsRule(Path.of(base, ProjectRules.AGENTS_FILE), dir = "")?.let { loaded.add(it) }
    rules = Cached(loaded, stamp)
    return loaded
  }

  /**
   * Правило из `AGENTS.md`, если он есть и не пуст.
   *
   * Формат без заголовка — обычный markdown, поэтому `alwaysApply` здесь не читается из файла, а
   * стоит по смыслу самой конвенции: `AGENTS.md` — это «читай всегда», а не «читай по просьбе».
   *
   * **Имя правила — путь, а не имя файла**, и это не косметика. Правила опознаются по имени: по
   * нему их складывает `applicable` и по нему же схлопывает `nearestWins`. У курсорных правил имя
   * даёт файл и оно разное, а у `AGENTS.md` оно одно на весь репозиторий — и ход, тронувший файлы
   * в двух пакетах сразу, молча потерял бы правила одного из них. Путь возвращает файлу его
   * настоящую личность: корневой и пакетные живут рядом, соседи друг друга не перекрывают, а
   * более близкий к файлу оказывается в подсказке ниже — там, где его и прочтут последним.
   */
  private fun agentsRule(path: Path, dir: String): ProjectRules.Rule? {
    if (!Files.isRegularFile(path)) return null
    val text = runCatching { Files.readString(path) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return ProjectRules.Rule(
      name = if (dir.isEmpty()) ProjectRules.AGENTS_FILE else "$dir/${ProjectRules.AGENTS_FILE}",
      dir = dir,
      description = null,
      globs = emptyList(),
      alwaysApply = true,
      source = if (dir.isEmpty()) ProjectRules.AGENTS_FILE else "$dir/${ProjectRules.AGENTS_FILE}",
      // Планку длины держит `promptBlock` — обрезать ещё и здесь значит завести ей второе место.
      body = text,
    )
  }

  private fun stampOf(path: Path): Long =
    runCatching { if (Files.exists(path)) Files.getLastModifiedTime(path).toMillis() else 0L }.getOrDefault(0L)

  companion object {
    /** `\n` is not a path character on any platform we ship to, unlike `:` and `;`. */
    private const val PATH_SEPARATOR = "\n"
    private const val KEY_REFERENCE_FOLDERS = "vibe.agent.referenceFolders"
    private const val KEY_SOURCE_FOLDERS = "vibe.agent.sourceFolders"

    fun getInstance(project: Project): ProjectContextService = project.service()
  }
}
