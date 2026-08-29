// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

/**
 * Remembers completions so typing does not pay for the same request twice.
 *
 * The key is not the raw prefix: a person typing inside a line changes the text on every keystroke
 * while the meaningful context stays the same. Normalising the prefix (a single trailing newline,
 * left indentation dropped) is what turns a cache that never hits into one that does — the trick is
 * taken from VibeIDE, where it is the difference between an autocomplete that feels instant and one
 * that costs a request per character.
 *
 * Bounded LRU: an unbounded cache in an editor is a memory leak with good intentions.
 */
class FimCache(private val capacity: Int = DEFAULT_CAPACITY) {
  private val entries = object : LinkedHashMap<String, String>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
      val evict = size > capacity
      if (evict) evictions++
      return evict
    }
  }

  var hits: Long = 0
    private set
  var misses: Long = 0
    private set
  var evictions: Long = 0
    private set

  @Synchronized
  fun get(key: String): String? {
    val value = entries[key]
    if (value == null) misses++ else hits++
    return value
  }

  @Synchronized
  fun put(key: String, completion: String) {
    entries[key] = completion
  }

  @Synchronized
  fun clear() {
    entries.clear()
  }

  @Synchronized
  fun size(): Int = entries.size

  companion object {
    const val DEFAULT_CAPACITY = 200

    /**
     * Cache key. The file and caret position pin the place; the normalised prefix pins the meaning.
     */
    fun key(file: String, line: Int, column: Int, prefix: String): String =
      file + ":" + line + ":" + column + ":" + normalize(prefix).hashCode()

    /**
     * Keeps at most one trailing newline and drops leading indentation of every line — two edits
     * that do not change what the model would answer, so they must not change the key.
     */
    fun normalize(prefix: String): String {
      val trimmed = prefix.trimEnd()
      val tail = prefix.substring(trimmed.length)
      val withTail = if (tail.contains('\n')) trimmed + "\n" else trimmed
      return withTail.split("\n").joinToString("\n") { it.trimStart() }
    }
  }
}
