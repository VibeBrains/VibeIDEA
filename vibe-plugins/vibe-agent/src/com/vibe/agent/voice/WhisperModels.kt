// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.File

/**
 * Finding a whisper.cpp model the person already has, so the field in the settings need not be filled by hand
 *
 * Models are not bundled and not downloaded here: a person who ran `brew install whisper-cpp` and fetched one model
 * has it in one of the places the tools of that ecosystem use, and asking them to point at it is asking for what the
 * machine already knows
 * What is found is used and shown, never written into the settings: a path the person did not type is not theirs
 * A path they did type always wins, including a wrong one, which is then named instead of being replaced by a guess
 *
 * The folders hold more than models. Homebrew ships a test model of half a megabyte beside the real ones, and
 * whisper.cpp keeps its voice-activity model under the same `ggml-` prefix, so a name is not enough:
 * [isModel] also holds a size floor and a list of marks of files that only look like models
 */
object WhisperModels {
  /** One file that may be a model */
  data class Candidate(val path: String, val bytes: Long) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
  }

  /** Where the model of the setting came from */
  sealed interface Resolved {
    /** The person's own path, which exists */
    data class Manual(val path: String) : Resolved

    /** Found on the machine, the setting is empty */
    data class Found(val path: String) : Resolved

    /** The person typed a path and there is no file there */
    data class ManualMissing(val path: String) : Resolved
    data object None : Resolved
  }

  private const val PREFIX = "ggml-"
  private const val SUFFIX = ".bin"

  /** The smallest real model (tiny, quantised) is tens of megabytes; the test model of Homebrew is half a megabyte */
  const val MIN_MODEL_BYTES = 10L * 1024 * 1024

  /** Names that carry the prefix and the suffix and are not speech models: the voice-activity model and the encoder files */
  private val NOT_MODELS = listOf("silero", "encoder")

  /** `ggml-base.en.bin`, `ggml-base.en-q5_1.bin`: the `.en` part, ended by a dash or the extension */
  private val ENGLISH_ONLY = Regex("""\.en([-.]|$)""")

  /** The language code for which an English-only model is not a wrong answer */
  private const val ENGLISH = "en"

  private const val CACHE_DIR = ".cache/whisper.cpp"

  /** The folders looked in, in the order of the list; the first two are the Homebrew prefixes of Apple Silicon and Intel */
  fun folders(home: String = System.getProperty("user.home")): List<File> = listOf(
    File(home, CACHE_DIR),
    File("/opt/homebrew/share/whisper-cpp"),
    File("/usr/local/share/whisper-cpp"),
  )

  fun isModel(candidate: Candidate): Boolean {
    val name = candidate.name.lowercase()
    return name.startsWith(PREFIX) && name.endsWith(SUFFIX) && candidate.bytes >= MIN_MODEL_BYTES && NOT_MODELS.none { it in name }
  }

  fun englishOnly(candidate: Candidate): Boolean = ENGLISH_ONLY.containsMatchIn(candidate.name.lowercase())

  /**
   * The largest model loaded in seconds rather than tens of them: [AUTO_MAX_BYTES], about the size of a quantised medium
   * tiny and base are quick, and on Russian they mishear one word in three; a model past this one loads for long enough
   * to read as a hang at the first stop
   */
  const val AUTO_MAX_BYTES = 600L * 1024 * 1024

  /**
   * The model to use among [candidates], or null
   *
   * A multilingual model first and an English-only one only when nothing else is there and the person speaks English:
   * an `.en` model fed Russian does not fail, it answers with confident nonsense
   * Among those the largest that is not larger than [AUTO_MAX_BYTES], because the larger hears better; when every one
   * is larger, the smallest of them, which still answers
   * A model the person names themselves is never second-guessed
   */
  fun pick(candidates: List<Candidate>, language: String): Candidate? {
    val models = candidates.filter { isModel(it) }
    val multilingual = models.filterNot { englishOnly(it) }
    val pool = multilingual.ifEmpty { if (language.trim().lowercase() == ENGLISH) models else emptyList() }
    // Equal sizes are decided by the path in both directions, so the answer does not depend on the order of the folders
    val larger = compareBy<Candidate> { it.bytes }.thenByDescending { it.path }
    val smaller = compareBy<Candidate>({ it.bytes }, { it.path })
    return pool.filter { it.bytes <= AUTO_MAX_BYTES }.maxWithOrNull(larger) ?: pool.minWithOrNull(smaller)
  }

  /** Every regular file of [folders], one level deep; a folder that is missing or unreadable has none */
  fun scan(folders: List<File> = folders()): List<Candidate> = folders.flatMap { folder ->
    runCatching { folder.listFiles().orEmpty().filter { it.isFile }.map { Candidate(it.path, it.length()) } }.getOrDefault(emptyList())
  }

  /** The model found on the machine for [language], or null */
  fun find(language: String, candidates: () -> List<Candidate> = { scan() }): Candidate? = pick(candidates(), language)

  /**
   * The model the settings and the machine give together: the person's path if there is one, else the one found
   *
   * @param manual the field «whisper.cpp model» as typed; blank means none
   */
  fun resolve(
    manual: String?,
    language: String,
    hasFile: (String) -> Boolean = { File(it).isFile },
    findModel: (String) -> Candidate? = { find(it) },
  ): Resolved {
    val typed = manual?.trim().orEmpty()
    if (typed.isNotEmpty()) return if (hasFile(typed)) Resolved.Manual(typed) else Resolved.ManualMissing(typed)
    return findModel(language)?.let { Resolved.Found(it.path) } ?: Resolved.None
  }
}
