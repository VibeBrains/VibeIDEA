// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.i18n

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Interface strings: the base language is compiled in, every other language is a file the user can
 * see and edit.
 *
 * The point is not "two languages" but that a person can make themselves a language — Elvish, if
 * they like — without us shipping anything. So:
 *
 * - the BASE catalogue is bundled in the plugin and also acts as the backing layer: a key missing
 *   from a translation falls back to the base string rather than to an empty button. Half a
 *   translation is already useful;
 * - every other language is a file in `~/.vibe/lang/<code>.json`, next to the user's own folders
 *   and not in an application-support directory nobody opens. A file appears — the language is in
 *   the list; a file is deleted — it is not;
 * - OUR second language (English) ships as such a file too. If English lives by the same rules as
 *   someone's Elvish, the rules have been tested on ourselves;
 * - the base language file is deliberately NOT seeded: a file always beats the binary, so a seeded
 *   copy would freeze today's wording for good — a fixed typo would land in the binary and stay
 *   covered by the old file;
 * - the folder is read ONCE at startup and lives in memory: deleting files under a running IDE
 *   breaks nothing, and no label ever waits for a disk.
 */
object VibeI18n {
  private val log = logger<VibeI18n>()
  private val json = Json { ignoreUnknownKeys = true }

  const val BASE_LANGUAGE = "ru"
  const val LANG_DIR = "lang"
  private const val KEY_LANGUAGE = "vibe.ui.language"

  /** `~/.vibe/lang` — beside the user's own configuration, not hidden in a support folder. */
  fun langDir(): Path = Path.of(System.getProperty("user.home"), ".vibe", LANG_DIR)

  private val base: Map<String, String> by lazy { loadBase() }

  @Volatile private var loaded: Map<String, Map<String, String>> = emptyMap()

  @Volatile private var activeCode: String = BASE_LANGUAGE

  @Volatile private var active: Map<String, String> = emptyMap()

  /** Reads the folder once. Safe to call again (settings change); never called per string. */
  @Synchronized
  fun reload() {
    loaded = readLanguages()
    activeCode = resolveActive(stored(), Locale.getDefault().language, loaded.keys)
    active = loaded[activeCode].orEmpty()
    log.info("VibeIDEA язык интерфейса: $activeCode; доступно: ${(loaded.keys + BASE_LANGUAGE).sorted()}")
  }

  /**
   * The string for [key], with named substitutions.
   *
   * A missing translation falls through to the base; a missing base key returns the key itself, so
   * a forgotten string is visible in the UI instead of silently rendering as emptiness.
   */
  fun t(key: String, vararg args: Pair<String, Any?>): String {
    val template = active[key] ?: base[key] ?: key
    return substitute(template, args.toMap())
  }

  /** Flat map for other surfaces (native menus, notifications): merged HERE, once. */
  fun map(): Map<String, String> = base + active

  /** Languages a person may choose: the base plus whatever files are on disk. */
  fun available(): List<String> = (listOf(BASE_LANGUAGE) + loaded.keys).distinct().sorted()

  fun activeCode(): String = activeCode

  fun setLanguage(code: String) {
    PropertiesComponent.getInstance().setValue(KEY_LANGUAGE, code, BASE_LANGUAGE)
    reload()
  }

  private fun stored(): String? = PropertiesComponent.getInstance().getValue(KEY_LANGUAGE)

  // --- pure seams (this is what the tests drive) ---

  /**
   * A saved code whose file is gone falls back to the system language, and that to the base.
   * Offering a language that does not exist is a way to show an empty interface.
   */
  fun resolveActive(saved: String?, systemLanguage: String, present: Set<String>): String = when {
    saved != null && (saved == BASE_LANGUAGE || saved in present) -> saved
    systemLanguage in present -> systemLanguage
    else -> BASE_LANGUAGE
  }

  /**
   * Named placeholders are part of the contract: word order differs between languages, so a
   * translator moves `{count}` around but never renames it. A placeholder with no value is LEFT
   * VISIBLE — a `{count}` on screen names the bug, while silently dropping it hides one.
   */
  fun substitute(template: String, args: Map<String, Any?>): String {
    if (args.isEmpty() || '{' !in template) return template
    var result = template
    for ((name, value) in args) {
      if (value == null) continue
      result = result.replace("{$name}", value.toString())
    }
    return result
  }

  /** Flat dotted keys only — nesting is prettier in an editor and worse at everything else. */
  fun parse(text: String): Map<String, String> {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
    return obj.entries.mapNotNull { (key, value) ->
      // `isString` matters: without it a number in the file arrives as the text "42", and a typo
      // in someone's translation would quietly become a label.
      val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return@mapNotNull null
      if (!primitive.isString) return@mapNotNull null
      key to primitive.content
    }.toMap()
  }

  /** A file name is the language code: `en.json` → `en`; anything else is not a language. */
  fun codeOf(fileName: String): String? {
    if (!fileName.endsWith(".json")) return null
    val code = fileName.removeSuffix(".json").lowercase()
    return if (code.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?"))) code else null
  }

  // --- io ---

  private fun loadBase(): Map<String, String> {
    val text = VibeI18n::class.java.getResourceAsStream("/lang/base.json")?.bufferedReader()?.readText()
    if (text == null) {
      log.warn("базовый каталог строк не найден в ресурсах плагина — интерфейс покажет ключи")
      return emptyMap()
    }
    return parse(text)
  }

  private fun readLanguages(): Map<String, Map<String, String>> {
    val dir = langDir()
    if (!Files.isDirectory(dir)) return emptyMap()
    val files = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
    val result = LinkedHashMap<String, Map<String, String>>()
    for (path in files) {
      val code = codeOf(path.fileName.toString()) ?: continue
      // The base file is not seeded, but a user may create one to override wording — honoured.
      val strings = runCatching { parse(Files.readString(path)) }.getOrDefault(emptyMap())
      if (strings.isNotEmpty()) result[code] = strings
    }
    return result
  }
}
